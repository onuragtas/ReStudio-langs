package org.restudio.cases;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AutowiringCases {
  // Expected: "Could not autowire. No beans of 'NotABean' type found."
  @Autowired
  private NotABean missing;

  // Expected: "Could not autowire. There is more than one bean of 'Notifier' type."
  @Autowired
  private Notifier ambiguous;

  // Expected: no problem. The field name selects the bean.
  @Autowired
  private Notifier mailNotifier;

  // Expected: no problem.
  @Autowired
  @Qualifier("smsNotifier")
  private Notifier qualified;

  // Expected: "Cannot find bean with qualifier 'pager'".
  @Autowired
  @Qualifier("pager")
  private Notifier unknownQualifier;

  // Expected: "Could not autowire. No beans of 'RestTemplate' type found." Spring Boot gives only a RestTemplateBuilder.
  @Autowired
  private RestTemplate restTemplate;
}
