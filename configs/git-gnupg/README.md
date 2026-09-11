# Git commit verification keyring

This directory is mounted read-only into `workflow-service`. Import only the
public OpenPGP keys allowed to sign managed Spark source commits. Never place a
private key or credential in this directory.

The JGit verifier reads a GnuPG `pubring.kbx` or `pubring.gpg`. In production,
set `AETHERIS_GIT_GPG_HOME` to an independently managed read-only directory
instead of copying operational key material into the repository checkout.
After changing the mounted keyring, restart `workflow-service` so JGit discards
its in-process public-key lookup cache. Add replacement keys before switching a
repository fingerprint policy, then remove retired keys in a later restart.

Repository registration stores full trusted signer fingerprints separately.
A required-signature repository is accepted only when the commit signature is
cryptographically valid and its full fingerprint is in that repository's
allowlist. Optional policy permits unsigned commits, but a present invalid
signature still fails closed.
