package fr.irisa.trustlens.zt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ZtStartupLogger {
    private static final Logger log = LoggerFactory.getLogger(ZtStartupLogger.class);
    private final ZtProperties props;

    public ZtStartupLogger(ZtProperties props) { this.props = props; }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() { log.info("[zt] {}", props); }
}
