/*
 * Copyright 2026 Zhang Cong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.tujincode.agentbridge.spring.support;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Minimal application used by the identity tests.
 *
 * <p>{@code @EnableMethodSecurity} is the switch that makes {@code @PreAuthorize} real.
 * Without it the annotation on the demo service is inert and every test below would
 * pass for the wrong reason — which is exactly the trap this fixture exists to avoid.
 */
@SpringBootApplication
@EnableMethodSecurity
public class SecurityTestApplication {
}
