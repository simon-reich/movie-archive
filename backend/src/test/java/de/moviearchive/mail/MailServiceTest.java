package de.moviearchive.mail;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.moviearchive.AbstractIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MailServiceTest extends AbstractIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    private MailService mailService;

    @Test
    void shouldSendVerificationEmail_withTokenLinkInBody() throws Exception {
        mailService.sendVerificationEmail("user@example.com", "test-raw-token-abc");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(received[0].getSubject()).contains("Verify");
        String body = (String) received[0].getContent();
        assertThat(body).contains("verify-email?token=test-raw-token-abc");
    }

    @Test
    void shouldSendPasswordResetEmail_withResetLinkInBody() throws Exception {
        mailService.sendPasswordResetEmail("user@example.com", "reset-raw-token-xyz");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).contains("Reset");
        String body = (String) received[0].getContent();
        assertThat(body).contains("reset-password?token=reset-raw-token-xyz");
    }
}
