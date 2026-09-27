/**
 * Cross-cutting infrastructure (web error handling, i18n, idempotency, crypto) usable by every module.
 * Contains no business logic and must not depend on any business module.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package com.mobility.core.shared;

import org.springframework.modulith.ApplicationModule;
