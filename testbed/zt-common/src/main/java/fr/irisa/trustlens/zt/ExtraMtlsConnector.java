package fr.irisa.trustlens.zt;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Closed-loop experiment only (ZT_MTLS_EXTRA_PORT set): exposes a second,
 * mutually-authenticated HTTPS connector next to the plain one, so that p3 on
 * the gateway→service hop can be switched at runtime by pointing the gateway
 * at one connector or the other (nginx hot reload).
 */
@Component
@ConditionalOnProperty(name = "ZT_MTLS_EXTRA_PORT")
public class ExtraMtlsConnector implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Value("${ZT_MTLS_EXTRA_PORT}")                        private int    port;
    @Value("${ZT_MTLS_KEYSTORE:/certs/keystore.p12}")      private String keystore;
    @Value("${ZT_MTLS_TRUSTSTORE:/certs/truststore.p12}")  private String truststore;
    @Value("${ZT_MTLS_STORE_PASSWORD:changeit}")           private String password;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        Connector c = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        c.setPort(port);
        c.setScheme("https");
        c.setSecure(true);
        Http11NioProtocol proto = (Http11NioProtocol) c.getProtocolHandler();
        proto.setSSLEnabled(true);
        proto.setMaxKeepAliveRequests(-1);
        proto.setKeepAliveTimeout(60_000);

        SSLHostConfig host = new SSLHostConfig();
        host.setCertificateVerification("required");
        host.setTruststoreFile(truststore);
        host.setTruststorePassword(password);
        host.setTruststoreType("PKCS12");
        SSLHostConfigCertificate cert =
            new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
        cert.setCertificateKeystoreFile(keystore);
        cert.setCertificateKeystorePassword(password);
        cert.setCertificateKeystoreType("PKCS12");
        host.addCertificate(cert);
        proto.addSslHostConfig(host);
        factory.addAdditionalTomcatConnectors(c);
    }
}
