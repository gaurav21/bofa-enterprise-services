package com.bofa.notifications.config;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;

import javax.jms.ConnectionFactory;
import javax.jms.JMSException;

/**
 * IBM MQ connection configuration for notification message consumption.
 * Connects to the enterprise MQ cluster for fraud, transaction, and balance events.
 */
@Configuration
public class MqConfig {

    @Value("${ibm.mq.queue-manager}")
    private String queueManager;

    @Value("${ibm.mq.channel}")
    private String channel;

    @Value("${ibm.mq.conn-name}")
    private String connName;

    @Value("${ibm.mq.user}")
    private String mqUser;

    @Value("${ibm.mq.password}")
    private String mqPassword;

    @Bean
    public ConnectionFactory mqConnectionFactory() throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setQueueManager(queueManager);
        factory.setChannel(channel);
        factory.setConnectionNameList(connName);
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.USERID, mqUser);
        factory.setStringProperty(WMQConstants.PASSWORD, mqPassword);

        CachingConnectionFactory cachingFactory = new CachingConnectionFactory(factory);
        cachingFactory.setSessionCacheSize(20);
        cachingFactory.setReconnectOnException(true);
        return cachingFactory;
    }

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ConnectionFactory mqConnectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(mqConnectionFactory);
        factory.setConcurrency("5-20");
        factory.setSessionTransacted(true);
        factory.setErrorHandler(t -> {
            // TODO: Route to dead-letter queue and alert PagerDuty
            System.err.println("MQ listener error: " + t.getMessage());
        });
        return factory;
    }
}
