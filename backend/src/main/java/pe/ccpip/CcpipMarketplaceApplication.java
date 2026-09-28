package pe.ccpip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CcpipMarketplaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcpipMarketplaceApplication.class, args);
    }
}
