package org.cardanofoundation.keriui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan({
        "org.cardanofoundation.keriui.domain.entity"
})
@EnableJpaRepositories({
        "org.cardanofoundation.keriui.domain.repository"
})
public class KeriUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeriUiApplication.class, args);
    }

}
