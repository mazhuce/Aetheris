import { describe, expect, it } from 'vitest'
import { credentialInput, readConnectionInput } from './credentialInput'

const base = { name: 'fixture', type: 'postgresql', host: '127.0.0.1', username: 'reader', password: 'read' }
describe('separate business credentials input', () => {
  it('requires an explicit disable operation when an existing writer is switched off', () => {
    expect(credentialInput({ ...base, writeEnabled: false, writeUsername: 'writer', writePassword: 'private' }, true)).toEqual({ ...base, disableWriteCredentials: true })
    expect(credentialInput({ ...base, writeEnabled: false }, false)).toEqual(base)
  })
  it('keeps writer input only when enabled and removes form state', () => {
    expect(credentialInput({ ...base, writeEnabled: true, writeUsername: 'writer', writePassword: 'write' }, false)).toEqual({ ...base, writeUsername: 'writer', writePassword: 'write' })
  })
  it('never sends writer secrets to a READ-only health probe', () => {
    expect(readConnectionInput({ ...base, writeEnabled: true, writeUsername: 'writer', writePassword: 'private', disableWriteCredentials: true })).toEqual(base)
  })
})
