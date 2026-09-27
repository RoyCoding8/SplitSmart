# Security Policy

## Supported versions

Only the latest released version receives fixes. SplitSmart is new, so there is no long-term-support matrix to maintain.

## Reporting a vulnerability

Please report security issues privately rather than opening a public issue.

Use GitHub's private vulnerability reporting on this repository if it is enabled. If it is not, open an issue that says only that you would like to discuss a security problem privately, without any technical detail, and the maintainer will arrange a private channel.

Please include:

- What the issue is and what an attacker gains
- Steps to reproduce, or a proof of concept
- The SplitSmart version and your Android version

You can expect an acknowledgement within a week. Fixes land in the next release unless the report requires more work, in which case we will agree a timeline with you.

## Scope

SplitSmart holds expense, group, and settlement data for a group of people. Treat any way to read or corrupt another user's data as in scope, along with anything that lets an app outside SplitSmart read the app's private storage.

Out of scope are crashes and bugs that require physical access to an already-unlocked device, since anyone with that access already has the data.
