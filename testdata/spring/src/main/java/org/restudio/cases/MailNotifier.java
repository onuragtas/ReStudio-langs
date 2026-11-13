package org.restudio.cases;

import org.springframework.stereotype.Component;

@Component
public class MailNotifier implements Notifier {
  @Override
  public void send(String text) {
  }
}
