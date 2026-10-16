package fr.irisa.trustlens.msuser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "fr.irisa.trustlens")
public class MsUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(MsUserApplication.class, args);
    }
}
