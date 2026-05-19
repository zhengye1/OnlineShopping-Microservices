package com.onlineshopping.product.service;

import com.onlineshopping.product.entity.OutboxEvent;
import com.onlineshopping.product.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository outboxRepo;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        // @Value field — Mockito constructor injection 唔 cover，要 ReflectionTestUtils
        ReflectionTestUtils.setField(poller, "productEventsTopic", "product-events");
    }

    @Test
    void publishesPending_setsPublishedAt_whenKafkaAcks() {
        OutboxEvent event = OutboxEvent.builder()
                .id(1L)
                .eventType("ProductCreated")
                .aggregateId("12345")
                .payload("{\"eventId\":\"abc\"}")
                .build();
        when(outboxRepo.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));

        RecordMetadata md = new RecordMetadata(new TopicPartition("product-events", 4),
                0L, 0, 0L, 0, 0);
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(sendResult.getRecordMetadata()).thenReturn(md);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        poller.publishPending();

        // ProducerRecord 嘅 invariants
        ArgumentCaptor<ProducerRecord<String, String>> recCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recCaptor.capture());
        ProducerRecord<String, String> r = recCaptor.getValue();
        assertThat(r.topic()).isEqualTo("product-events");
        assertThat(r.key()).isEqualTo("12345");                       // productId partition key
        assertThat(r.value()).contains("\"eventId\":\"abc\"");
        assertThat(new String(r.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("ProductCreated");

        // published_at 必須 set
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxRepo).saveAll(List.of(event));
    }

    @Test
    void leavesPublishedAtNull_whenKafkaFails_forRetryNextPoll() {
        OutboxEvent event = OutboxEvent.builder()
                .id(2L)
                .eventType("ProductCreated")
                .aggregateId("99")
                .payload("{}")
                .build();
        when(outboxRepo.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

        poller.publishPending();   // 唔可以 throw — 失敗要 isolate

        assertThat(event.getPublishedAt()).isNull();           // 仍然 pending
        verify(outboxRepo).saveAll(List.of(event));            // 整個 batch saveAll
    }

    @Test
    void noOp_whenNoPending() {
        when(outboxRepo.findTop100ByPublishedAtIsNullOrderByIdAsc()).thenReturn(List.of());

        poller.publishPending();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxRepo, never()).saveAll(any());
    }
}