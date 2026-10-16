package fr.irisa.trustlens.mslocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "fr.irisa.trustlens")
public class MsLocationApplication {
    public static void main(String[] args) {
        SpringApplication.run(MsLocationApplication.class, args);
    }
}
