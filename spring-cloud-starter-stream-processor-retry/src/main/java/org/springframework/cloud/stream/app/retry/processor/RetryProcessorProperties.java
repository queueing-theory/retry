package org.springframework.cloud.stream.app.retry.processor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import javax.validation.constraints.NotNull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

@ConfigurationProperties("retry")
public class RetryProcessorProperties {

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration duration;

    @NotNull
    public Duration getDuration() {
        return duration;
    }

    public void setDuration(Duration duration) {
        this.duration = duration;
    }

}
