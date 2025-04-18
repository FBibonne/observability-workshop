package com.worldline.easypay.payment.control;

import com.worldline.easypay.EasypayServiceApplication;
import com.worldline.easypay.cardref.control.CardType;
import com.worldline.easypay.payment.control.bank.BankAuthorService;
import com.worldline.easypay.payment.control.track.PaymentTracker;
import com.worldline.easypay.payment.entity.Payment;
import com.worldline.easypay.payment.entity.PaymentRepository;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    private CardValidator cardValidator;
    private PosValidator posValidator;
    private PaymentRepository paymentRepository;
    private BankAuthorService bankAuthorService;
    private PaymentTracker paymentTracker;

    private LongHistogram processHistogram;  // (1)
    private LongHistogram storeHistogram;
    private LongCounter requestCounter;


    @Value("${payment.author.threshold:10000}")
    Integer authorThreshold;

    public PaymentService(CardValidator cardValidator, PosValidator posValidator, PaymentRepository paymentRepository, BankAuthorService bankAuthorService, PaymentTracker paymentTracker) {
        this.cardValidator = cardValidator;
        this.posValidator = posValidator;
        this.paymentRepository = paymentRepository;
        this.bankAuthorService = bankAuthorService;
        this.paymentTracker = paymentTracker;
        OpenTelemetry openTelemetry = GlobalOpenTelemetry.get();
        processHistogram = openTelemetry.getMeter(EasypayServiceApplication.class.getName())  //(3)
                .histogramBuilder("devoxx.payment.process")  // (4)
                .setDescription("Payment processing time") // (5)
                .setUnit("ms") // (6)
                .ofLongs() // (7)
                .build();
        storeHistogram = openTelemetry.getMeter(EasypayServiceApplication.class.getName())
                .histogramBuilder("devoxx.payment.store")
                .setDescription("Payment storing time")
                .setUnit("ms")
                .ofLongs()
                .build();
                requestCounter = openTelemetry.getMeter(EasypayServiceApplication.class.getName()) // (2)
                .counterBuilder("devoxx.payment.requests")
                .setDescription("Payment requests counter")
                .build();
    }

    private void process(PaymentProcessingContext context) {
        long startTime = System.currentTimeMillis(); // (1)
        try { 
        if (!posValidator.isActive(context.posId)) {
            context.responseCode = PaymentResponseCode.INACTIVE_POS;
            return;
        }

        if (!cardValidator.checkCardNumber(context.cardNumber)) {
            context.responseCode = PaymentResponseCode.INVALID_CARD_NUMBER;
            return;
        }

        CardType cardType = cardValidator.checkCardType(context.cardNumber);
        if (cardType == CardType.UNKNOWN) {
            context.responseCode = PaymentResponseCode.UNKNWON_CARD_TYPE;
            return;
        }
        context.cardType = cardType;

        if (cardValidator.isBlackListed(context.cardNumber)) {
            context.responseCode = PaymentResponseCode.BLACK_LISTED_CARD_NUMBER;
            return;
        }

        if (context.amount > authorThreshold) {
            if (!bankAuthorService.authorize(context)) {
                LOG.info("Authorization refused by bank, context=" + context);
                context.responseCode = context.processingMode.equals(ProcessingMode.STANDARD) ? PaymentResponseCode.AUTHORIZATION_DENIED : PaymentResponseCode.AMOUNT_EXCEEDED;
            }
        }}finally {
            long duration = System.currentTimeMillis() - startTime; // (3)
            processHistogram.record(duration); // (4)
        }

    }

    private void store(PaymentProcessingContext context) {
        long startTime = System.currentTimeMillis();
        try{
        Payment payment = new Payment();

        payment.amount = context.amount;
        payment.cardNumber = context.cardNumber;
        payment.expiryDate = context.expiryDate;
        payment.responseCode = context.responseCode;
        payment.processingMode = context.processingMode;
        payment.cardType = context.cardType;
        payment.posId = context.posId;
        payment.dateTime = context.dateTime;
        payment.responseTime = System.currentTimeMillis() - context.responseTime;
        context.responseTime = payment.responseTime;
        payment.bankCalled = context.bankCalled;
        payment.authorized = context.authorized;
        if (context.authorId.isPresent()) {
            payment.authorId = context.authorId.get();
        }

        paymentRepository.saveAndFlush(payment);

        context.id = payment.id;
    } finally {
        long duration = System.currentTimeMillis() - startTime;
        storeHistogram.record(duration);
    }
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void accept(PaymentProcessingContext paymentContext) {
        requestCounter.add(1);
        process(paymentContext);
        store(paymentContext);
        paymentTracker.track(paymentContext);
    }

    public Optional<Payment> findById(UUID id) {
        return paymentRepository.findById(id);
    }

    public List<Payment> findAll() {
        return paymentRepository.findAll();
    }

    public long count() {
        return paymentRepository.count();
    }

}
