import type { DataSourceInput } from '@/api/metadata'

export interface CredentialFormValues extends DataSourceInput { writeEnabled?: boolean }

export function credentialInput(values: CredentialFormValues, previouslyConfigured: boolean): DataSourceInput {
  const { writeEnabled, writeUsername, writePassword, disableWriteCredentials: _ignored, ...read } = values
  if (!writeEnabled) return { ...read, ...(previouslyConfigured ? { disableWriteCredentials: true } : {}) }
  return { ...read, writeUsername, writePassword }
}

/** SELECT 1 tests only READ; never send unrelated writer secrets to that endpoint. */
export function readConnectionInput(values: CredentialFormValues): DataSourceInput {
  const { writeEnabled: _enabled, writeUsername: _user, writePassword: _password, disableWriteCredentials: _disabled, ...read } = values
  return read
}
