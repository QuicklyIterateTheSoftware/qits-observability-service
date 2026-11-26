package eu.wohlben.qits.telemetry.issues;

/**
 * Where an error issue stands. OPEN until a person dismisses it; a DISMISSED issue whose
 * fingerprint occurs again is OPEN again, as a regression (owner ruling 2026-11-22).
 */
public enum ErrorIssueState {
  OPEN,
  DISMISSED
}
