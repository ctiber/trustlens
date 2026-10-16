package fr.irisa.trustlens.msnotification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "fr.irisa.trustlens")
public class MsNotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(MsNotificationApplication.class, args);
    }
}
