package fr.irisa.trustlens.zt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Runtime posture switch (closed-loop experiment). Not routed by the gateway.
 *   GET  /zt/posture                       current flags
 *   POST /zt/posture {"ac4a":true,"ra":true,"raLevel":1.0,"mtls":true}
 * p4 (secret rotation) is fixed at start-up: the key provider is chosen then.
 * p3 is enforced by the gateway configuration; the flag here is informational.
 */
@RestController
public class ZtPostureController {

    private static final Logger log = LoggerFactory.getLogger(ZtPostureController.class);
    private final ZtProperties props;

    public ZtPostureController(ZtProperties props) { this.props = props; }

    @GetMapping("/zt/posture")
    public Map<String, Object> get() {
        return Map.of("ac4a", props.ac4a, "mtls", props.mtls, "sr", props.sr,
                      "srLevel", props.srLevel, "ra", props.ra, "raLevel", props.raLevel,
                      "ac4aShadow", props.ac4aShadow, "raShadowLevel", props.raShadowLevel);
    }

    @PostMapping("/zt/posture")
    public Map<String, Object> set(@RequestBody Map<String, Object> body) {
        if (body.containsKey("ac4a"))    props.ac4a    = Boolean.parseBoolean(body.get("ac4a").toString());
        if (body.containsKey("mtls"))    props.mtls    = Boolean.parseBoolean(body.get("mtls").toString());
        if (body.containsKey("ra"))      props.ra      = Boolean.parseBoolean(body.get("ra").toString());
        if (body.containsKey("raLevel")) props.raLevel = Double.parseDouble(body.get("raLevel").toString());
        if (body.containsKey("ac4aShadow"))    props.ac4aShadow    = Boolean.parseBoolean(body.get("ac4aShadow").toString());
        if (body.containsKey("raShadowLevel")) props.raShadowLevel = Double.parseDouble(body.get("raShadowLevel").toString());
        log.info("[zt] posture switched: {}", props);
        return get();
    }
}
