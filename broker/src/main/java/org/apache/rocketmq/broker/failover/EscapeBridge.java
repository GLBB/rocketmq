/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.broker.failover;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.GetMessageResult;
import org.apache.rocketmq.common.message.MessageExtBrokerInner;
import org.apache.rocketmq.store.MessageStore;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;

public class EscapeBridge {
    protected static final InternalLogger LOG = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private final String innerProducerGroupName;
    private final String innerConsumerGroupName;

    private final BrokerController brokerController;

    private DefaultMQProducer innerProducer;
    private DefaultMQPullConsumer innerConsumer;

    public EscapeBridge(BrokerController brokerController) {
        this.brokerController = brokerController;
        this.innerProducerGroupName = "InnerProducerGroup_" + brokerController.getBrokerConfig().getBrokerName() + "_" + brokerController.getBrokerConfig().getBrokerId();
        this.innerConsumerGroupName = "InnerConsumerGroup_" + brokerController.getBrokerConfig().getBrokerName() + "_" + brokerController.getBrokerConfig().getBrokerId();
    }

    public void start() throws Exception {
        if (brokerController.getBrokerConfig().isEnableSlaveActingMaster() && brokerController.getBrokerConfig().isEnableRemoteEscape()) {
            String nameserver = this.brokerController.getNameServerList();
            if (nameserver != null && !nameserver.isEmpty()) {
                startInnerProducer(nameserver);
                startInnerConsumer(nameserver);
                LOG.info("start inner producer and consumer success.");
            } else {
                throw new RuntimeException("nameserver address is null or empty");
            }
        }
    }

    public void shutdown() {
        if (this.innerProducer != null) {
            this.innerProducer.shutdown();
        }

        if (this.innerConsumer != null) {
            this.innerConsumer.shutdown();
        }
    }

    private void startInnerProducer(String nameServer) throws MQClientException {
        try {
            innerProducer = new DefaultMQProducer(innerProducerGroupName);
            innerProducer.setNamesrvAddr(nameServer);
            innerProducer.start();
        } catch (MQClientException e) {
            LOG.error("start inner producer failed, nameserver address: {}", nameServer, e);
            throw e;
        }
    }

    private void startInnerConsumer(String nameServer) throws MQClientException {
        try {
            innerConsumer = new DefaultMQPullConsumer(innerConsumerGroupName);
            innerConsumer.setNamesrvAddr(nameServer);
            innerConsumer.start();
        } catch (MQClientException e) {
            LOG.error("start inner consumer failed, nameserver address: {}", nameServer, e);
            throw e;
        }
    }

    public PutMessageResult putMessage(MessageExtBrokerInner messageExt) {
        BrokerController masterBroker = this.brokerController.peekMasterBroker();
        if (masterBroker != null) {
            return masterBroker.getMessageStore().putMessage(messageExt);
        } else if (this.brokerController.getBrokerConfig().isEnableSlaveActingMaster()
            && this.brokerController.getBrokerConfig().isEnableRemoteEscape()
            && innerProducer != null) {
            // Remote Acting lead to born timestamp, msgId changed, it need to polish.
            try {
                messageExt.setWaitStoreMsgOK(false);
                SendResult sendResult = innerProducer.send(messageExt);
                return transformSendResult2PutResult(sendResult);
            } catch (Exception e) {
                LOG.error("sendMessageInFailover to remote failed", e);
                return new PutMessageResult(PutMessageStatus.PUT_TO_REMOTE_BROKER_FAIL, null, true);
            }
        } else {
            LOG.warn("Put message failed, enableSlaveActingMaster={}, enableRemoteEscape={}.",
                this.brokerController.getBrokerConfig().isEnableSlaveActingMaster(), this.brokerController.getBrokerConfig().isEnableRemoteEscape());
            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        }
    }

    public CompletableFuture<PutMessageResult> asyncPutMessage(MessageExtBrokerInner messageExt) {
        BrokerController masterBroker = this.brokerController.peekMasterBroker();
        CompletableFuture<PutMessageResult> completableFuture = new CompletableFuture<>();
        if (masterBroker != null) {
            return masterBroker.getMessageStore().asyncPutMessage(messageExt);
        } else if (this.brokerController.getBrokerConfig().isEnableSlaveActingMaster()
            && this.brokerController.getBrokerConfig().isEnableRemoteEscape()
            && innerProducer != null) {
            // Remote Acting lead to born timestamp, msgId changed, it need to polish.
            try {
                messageExt.setWaitStoreMsgOK(false);
                innerProducer.send(messageExt, new SendCallback() {
                    @Override public void onSuccess(SendResult sendResult) {
                        completableFuture.complete(transformSendResult2PutResult(sendResult));
                    }

                    @Override public void onException(Throwable e) {
                        completableFuture.complete(new PutMessageResult(PutMessageStatus.PUT_TO_REMOTE_BROKER_FAIL, null, true));
                    }
                });
                return completableFuture;
            } catch (Exception e) {
                LOG.error("sendMessageInFailover to remote failed", e);
                return CompletableFuture.completedFuture(new PutMessageResult(PutMessageStatus.PUT_TO_REMOTE_BROKER_FAIL, null, true));
            }
        } else {
            LOG.warn("Put message failed, enableSlaveActingMaster={}, enableRemoteEscape={}.",
                this.brokerController.getBrokerConfig().isEnableSlaveActingMaster(), this.brokerController.getBrokerConfig().isEnableRemoteEscape());
            return CompletableFuture.completedFuture(new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null));
        }
    }

    public PutMessageResult putMessageToSpecificQueue(MessageExtBrokerInner messageExt) {
        BrokerController masterBroker = this.brokerController.peekMasterBroker();
        if (masterBroker != null) {
            return masterBroker.getMessageStore().putMessage(messageExt);
        } else if (this.brokerController.getBrokerConfig().isEnableSlaveActingMaster()
            && this.brokerController.getBrokerConfig().isEnableRemoteEscape()
            && this.innerProducer != null) {
            try {
                messageExt.setWaitStoreMsgOK(false);
                // Remote Acting lead to born timestamp, msgId changed, it need to polish.
                SendResult sendResult = innerProducer.send(messageExt, new MessageQueueSelector() {
                    @Override
                    public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
                        String id = (String) arg;
                        int index = Math.abs(id.hashCode()) % mqs.size();
                        return mqs.get(index);
                    }
                }, messageExt.getTopic() + messageExt.getStoreHost());
                return transformSendResult2PutResult(sendResult);
            } catch (Exception e) {
                LOG.error("sendMessageInFailover to remote failed", e);
                return new PutMessageResult(PutMessageStatus.PUT_TO_REMOTE_BROKER_FAIL, null, true);
            }
        } else {
            LOG.warn("Put message to specific queue failed, enableSlaveActingMaster={}, enableRemoteEscape={}.",
                this.brokerController.getBrokerConfig().isEnableSlaveActingMaster(), this.brokerController.getBrokerConfig().isEnableRemoteEscape());
            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        }
    }

    private PutMessageResult transformSendResult2PutResult(SendResult sendResult) {
        if (sendResult == null) {
            return new PutMessageResult(PutMessageStatus.PUT_TO_REMOTE_BROKER_FAIL, null, true);
        }
        switch (sendResult.getSendStatus()) {
            case SEND_OK:
                return new PutMessageResult(PutMessageStatus.PUT_OK, null, true);
            case SLAVE_NOT_AVAILABLE:
                return new PutMessageResult(PutMessageStatus.SLAVE_NOT_AVAILABLE, null, true);
            case FLUSH_DISK_TIMEOUT:
                return new PutMessageResult(PutMessageStatus.FLUSH_DISK_TIMEOUT, null, true);
            case FLUSH_SLAVE_TIMEOUT:
                return new PutMessageResult(PutMessageStatus.FLUSH_SLAVE_TIMEOUT, null, true);
            default:
                return new PutMessageResult(PutMessageStatus.PUT_TO_REMOTE_BROKER_FAIL, null, true);
        }
    }

    public MessageExt getMessage(String topic, long offset, int queueId, String brokerName) {
        MessageStore messageStore = brokerController.getMessageStoreByBrokerName(brokerName);
        if (messageStore != null) {
            final GetMessageResult getMessageTmpResult = messageStore.getMessage(innerConsumerGroupName, topic, queueId, offset, 1, null);
            if (getMessageTmpResult == null) {
                LOG.warn("getMessageResult is null , innerConsumerGroupName {}, topic {}, offset {}, queueId {}", innerConsumerGroupName, topic, offset, queueId);
                return null;
            }
            List<MessageExt> list = decodeMsgList(getMessageTmpResult);
            if (list == null || list.isEmpty()) {
                LOG.warn("Can not get msg , topic {}, offset {}, queueId {}, result is {}", topic, offset, queueId, getMessageTmpResult);
                return null;
            } else {
                return list.get(0);
            }
        } else if (innerConsumer != null) {
            return getMessageFromRemote(topic, offset, queueId, brokerName);
        } else {
            return null;
        }
    }

    protected List<MessageExt> decodeMsgList(GetMessageResult getMessageResult) {
        List<MessageExt> foundList = new ArrayList<>();
        try {
            List<ByteBuffer> messageBufferList = getMessageResult.getMessageBufferList();
            if (messageBufferList != null) {
                for (int i = 0; i < messageBufferList.size(); i++) {
                    ByteBuffer bb = messageBufferList.get(i);
                    if (bb == null) {
                        LOG.error("bb is null {}", getMessageResult);
                        continue;
                    }
                    MessageExt msgExt = MessageDecoder.decode(bb);
                    if (msgExt == null) {
                        LOG.error("decode msgExt is null {}", getMessageResult);
                        continue;
                    }
                    // use CQ offset, not offset in Message
                    msgExt.setQueueOffset(getMessageResult.getMessageQueueOffset().get(i));
                    foundList.add(msgExt);
                }
            }
        } finally {
            getMessageResult.release();
        }

        return foundList;
    }

    protected MessageExt getMessageFromRemote(String topic, long offset, int queueId, String brokerName) {
        try {
            PullResult pullResult = innerConsumer.pull(new MessageQueue(topic, brokerName, queueId), "*", offset, 1);
            if (pullResult.getPullStatus().equals(PullStatus.FOUND)) {
                return pullResult.getMsgFoundList().get(0);
            }
        } catch (Exception e) {
            LOG.error("Get message from remote failed.", e);
        }

        return null;
    }
}
