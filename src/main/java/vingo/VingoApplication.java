package vingo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(systemName = "Vingo")
public class VingoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VingoApplication.class, args);
    }
}
