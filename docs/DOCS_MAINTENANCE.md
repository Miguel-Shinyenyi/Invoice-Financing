# Doc Maintenance Rules

This file controls how every other doc in this project gets written and updated. Read this before touching any code.

## Best practices rule

Every structural or architectural decision follows current industry best practice for fintech and distributed systems, not personal preference. When a decision is made, the doc entry states which best practice it follows and why it applies here. If best practice is unclear or debated, say so in the Open Questions section instead of picking arbitrarily.

## Core rule

Every code change that adds, removes, or changes behavior must come with a doc update in the same turn. No exceptions. Docs describe the current state of the system, not the history of how it was built. Old approaches get deleted from the doc, not commented out.

## When to update which file

- New or changed API endpoint: update `backend.md`
- New or changed database table, column, or index: update `database.md`
- New Kafka topic, event schema, or consumer: update `kafka-events.md`
- New auth rule, role, or permission check: update `security.md`
- New model feature, retrain, or scoring logic change: update `fraud-detection.md`
- New S3 bucket structure or file type stored: update `storage.md`
- New frontend page or component with backend dependency: update `frontend.md`
- New pipeline step, environment, or deployment change: update `cicd.md`
- New test category or coverage decision: update `testing.md`
- Any phase starting or finishing: update the Status log table in `PROJECT.md`

## Format for each component doc

Each file in `docs/` should follow this structure:

1. **Purpose**, one paragraph, what this component does and why it exists
2. **Current state**, what is built right now, described plainly
3. **Decisions log**, a table of decisions made and why, most recent first
4. **Open questions**, anything unresolved that affects this component

## Decisions log format

| Date | Decision | Reason |
|------|----------|--------|
| YYYY-MM-DD | What was decided | Why, in one sentence |

## Rules for writing these docs

- No filler. State what exists, not what might exist someday.
- No marketing language. Describe the system the way you'd explain it to another engineer joining the project.
- Keep each doc under 300 lines. If it grows past that, split it into sub-files and link them.
- When something is removed or replaced, delete the old description. Do not leave dead documentation in place.

## How this gets used

When work resumes on this project in a new session, read `PROJECT.md` first, then read the specific component doc for whatever is being worked on. That's enough context to continue without re-explaining the whole system.
