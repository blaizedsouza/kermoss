package io.kermoss.cmd.infra.transporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.kermoss.cmd.domain.OutboundCommand;
import io.kermoss.cmd.domain.event.OutboundCommandStarted;
import io.kermoss.cmd.domain.repository.CommandRepository;
import io.kermoss.cmd.infra.transporter.strategies.CommandTransporterStrategy;
import io.kermoss.infra.KermossTracer;
import io.kermoss.infra.KermossTxLogger;

import java.util.Arrays;
import java.util.List;

@Service
public class DefaultCommandTransporter extends AbstractCommandTransporter<OutboundCommandStarted>  {
    private final Logger log = LoggerFactory.getLogger(DefaultCommandTransporter.class);
    private final CommandRepository commandRepository;
    private final Environment environment;
    private final CommandTransporterStrategy strategy;
    private final KermossTracer tracer;
    private final KermossTxLogger txLogger;


    @Autowired
    public DefaultCommandTransporter(
        final ApplicationEventPublisher publisher,
        final CommandRepository commandRepository,
        final Environment environment,
        final KermossTracer tracer,
        final CommandTransporterStrategy strategy,
        final KermossTxLogger txLogger
    ) {
        super(publisher);
        this.commandRepository = commandRepository;
        this.environment = environment;
        this.tracer = tracer;
        this.strategy = strategy;
        this.txLogger = txLogger;
    }

    @Override
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEvent(final OutboundCommandStarted event) {
        log.info("Start Transport Event {}:{}", event.getId(), event.getMeta().getSubject());
        //the tracer must be closed (transaction completed) see KERMOSS Transaction eventListener
        tracer.startGtxSpan("Transporting OC of "+event.getMeta().getSubject(),event.getId(), event.getMeta().getTraceId(), tracer.currentSpan());
        this.commandRepository.findOutboundCommandOpt(event.getMeta().getCommandId()).ifPresent(outboundCommand -> {
            final List<OutboundCommand.Status> whiteList = Arrays.asList(OutboundCommand.Status.STARTED, OutboundCommand.Status.FAILED);
            if(whiteList.contains(outboundCommand.getStatus())) {
                final String source = environment.getProperty(
                    "kermoss.serviceName",
                    environment.getProperty("spring.application.name")
                );
                outboundCommand.setSource(source);

                if(this.strategy.transportCommand(transform(outboundCommand))) {
                    log.info("Success Transport Command over HTTP: {}", txLogger.printJsonObject(outboundCommand));
                    outboundCommand.changeStatusToDelivered();
                    log.info("OutboundCommand changed status to Delivered: {}", txLogger.printJsonObject(outboundCommand));
                } else {
                    log.info("Failure Transport Command over HTTP: {}", outboundCommand.getId());
                    outboundCommand.changeStatusToFailed();
                    log.info("OutboundCommand changed status to Failed: {}", txLogger.printJsonObject(outboundCommand));
                }
                commandRepository.save(outboundCommand);
                txLogger.logTransactionState(txstatus -> log.info( "Transaction {} for OutboundCommand: {}", txstatus, txLogger.printJsonObject(outboundCommand)));
            }
        });
    }


}
