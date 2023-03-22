package io.harness.cf.client.dto;

import java.util.Map;
import java.util.Set;

import io.harness.cf.client.common.StringUtils;
import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Target {

  private String name;
  private String identifier;

  @Singular private Map<String, Object> attributes;
  private boolean isPrivate; // If the target is private

  @Singular
  private Set<String> privateAttributes; // Custom set to set the attributes which are private

  @Override
  public String toString() {

    return "TargetId: " + identifier;
  }

  public boolean isValid() {

    return !StringUtils.isNullOrEmpty(identifier);
  }

  public io.harness.cf.model.Target ApiTarget() {
    return io.harness.cf.model.Target.builder()
        .identifier(getIdentifier())
        .name(getName())
        .attributes(getAttributes())
        .build();
  }
}
