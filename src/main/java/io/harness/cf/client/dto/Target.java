package io.harness.cf.client.dto;

import com.google.common.base.Strings;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.Singular;

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
    return !Strings.isNullOrEmpty(identifier);
  }
}
