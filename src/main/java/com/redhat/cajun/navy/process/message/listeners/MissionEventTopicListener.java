package com.redhat.cajun.navy.process.message.listeners;

import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.redhat.cajun.navy.process.message.model.Message;
import com.redhat.cajun.navy.process.message.model.MissionStartedEvent;
import com.redhat.cajun.navy.process.message.model.VictimDeliveredEvent;
import com.redhat.cajun.navy.process.message.model.VictimPickedUpEvent;
import org.jbpm.services.api.ProcessService;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.internal.KieInternalServices;
import org.kie.internal.process.CorrelationKey;
import org.kie.internal.process.CorrelationKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MissionEventTopicListener {

    private static final Logger log = LoggerFactory.getLogger(MissionEventTopicListener.class);

    private static final String TYPE_MISSION_STARTED_EVENT = "MissionStartedEvent";
    private static final String TYPE_VICTIM_PICKEDUP_EVENT = "VictimPickedUpEvent";
    private static final String TYPE_VICTIM_DELIVERED_EVENT = "VictimDeliveredEvent";
    private static final String[] ACCEPTED_MESSAGE_TYPES = {TYPE_MISSION_STARTED_EVENT, TYPE_VICTIM_PICKEDUP_EVENT, TYPE_VICTIM_DELIVERED_EVENT};

    private static final String SIGNAL_MISSION_STARTED = "MissionStarted";
    private static final String SIGNAL_VICTIM_PICKEDUP = "VictimPickedUp";
    private static final String SIGNAL_VICTIM_DELIVERED = "VictimDelivered";

    private CorrelationKeyFactory correlationKeyFactory = KieInternalServices.Factory.get().newCorrelationKeyFactory();

    @Autowired
    private ProcessService processService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @KafkaListener(topics = "${listener.destination.mission-event}")
    public void processMessage(@Payload String messageAsJson) {

        messageType(messageAsJson).ifPresent(s -> {
            switch (s) {
                case TYPE_MISSION_STARTED_EVENT:
                    processMissionStartedEvent(messageAsJson);
                    break;
                case TYPE_VICTIM_PICKEDUP_EVENT:
                    processVictimPickedUpEvent(messageAsJson);
                    break;
                case TYPE_VICTIM_DELIVERED_EVENT:
                    processVictimDeliveredEvent(messageAsJson);
                    break;
            }
        });
    }

    private void processMissionStartedEvent(String messageAsJson) {

        Message<MissionStartedEvent> message;
        try {
            message = new ObjectMapper().readValue(messageAsJson, new TypeReference<Message<MissionStartedEvent>>() {});
            String incidentId = message.getBody().getIncidentId();
            signalProcess(incidentId, SIGNAL_MISSION_STARTED);
       } catch (Exception e) {
            log.error("Error processing msg " + messageAsJson, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void processVictimPickedUpEvent(String messageAsJson) {

        Message<VictimPickedUpEvent> message;
        try {
            message = new ObjectMapper().readValue(messageAsJson, new TypeReference<Message<VictimPickedUpEvent>>() {});
            String incidentId = message.getBody().getIncidentId();
            signalProcess(incidentId, SIGNAL_VICTIM_PICKEDUP);
        } catch (Exception e) {
            log.error("Error processing msg " + messageAsJson, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void processVictimDeliveredEvent(String messageAsJson) {

        Message<VictimDeliveredEvent> message;
        try {
            message = new ObjectMapper().readValue(messageAsJson, new TypeReference<Message<VictimDeliveredEvent>>() {});
            String incidentId = message.getBody().getIncidentId();
            signalProcess(incidentId, SIGNAL_VICTIM_DELIVERED);
        } catch (Exception e) {
            log.error("Error processing msg " + messageAsJson, e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void signalProcess(String incidentId, String signal) {

        if (incidentId == null || incidentId.isEmpty()) {
            log.warn("Message contains no value for incidentId. Message cannot be processed!");
            return;
        }
        CorrelationKey correlationKey = correlationKeyFactory.newCorrelationKey(incidentId);
        new TransactionTemplate(transactionManager).execute((TransactionStatus s) -> {
            ProcessInstance processInstance = processService.getProcessInstance(correlationKey);
            if (processInstance == null) {
                log.warn("Process instance with correlationKey '" + incidentId + "' not found.");
                return null;
            }
            processService.signalProcessInstance(processInstance.getId(), signal, null);
            return null;
        });
    }

    private Optional<String> messageType(String messageAsJson) {
        try {
            String messageType = JsonPath.read(messageAsJson, "$.messageType");
            if (Arrays.asList(ACCEPTED_MESSAGE_TYPES).contains(messageType)) {
                return Optional.of(messageType);
            }
            log.debug("Message with type '" + messageType + "' is ignored");
        } catch (Exception e) {
            log.warn("Unexpected message without 'messageType' field.");
        }
        return Optional.empty();
    }
}