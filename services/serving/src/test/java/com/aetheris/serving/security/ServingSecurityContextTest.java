package com.aetheris.serving.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import static org.assertj.core.api.Assertions.assertThat;

class ServingSecurityContextTest {
    @Test
    void springConstructsTheRealSecurityBeanWithItsProductionConstructor() throws Exception {
        try(var fixture=new V2ServingFixture()){
        new ApplicationContextRunner()
                .withBean(AuthProtocolClient.class,()->fixture.auth)
                .withUserConfiguration(ServingSecurity.class)
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(ServingSecurity.class);
                    try{fixture.request(request->assertThat(context.getBean(ServingSecurity.class).requireIdentity(request).username()).isEqualTo("alice"));}
                    catch(Exception failure){throw new AssertionError(failure);}
                });
        }
    }
}
