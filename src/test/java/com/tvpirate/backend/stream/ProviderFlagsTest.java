package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The .env switches are the whole reason a dead provider can be turned off
 * without a code change. vault:streaming-providers-deep-dive#provider-flags
 */
class ProviderFlagsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void aProviderIsRegisteredWhenItsFlagIsOn() {
        runner.withUserConfiguration(NetmirrorProvider.class)
                .withPropertyValues("app.providers.netmirror.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(NetmirrorProvider.class));
    }

    @Test
    void aProviderIsAbsentWhenItsFlagIsOff() {
        runner.withUserConfiguration(NetmirrorProvider.class)
                .withPropertyValues("app.providers.netmirror.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NetmirrorProvider.class));
    }

    @Test
    void vixsrcIsOffWhenTheFlagIsMissing() {
        runner.withUserConfiguration(VixsrcProvider.class)
                .run(context -> assertThat(context).doesNotHaveBean(VixsrcProvider.class));
    }
}
