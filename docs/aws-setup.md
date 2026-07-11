# AWS Setup

## Purpose

Step-by-step setup for a new AWS account for this project, following security best practices from the start.

## Current state

Not yet set up. Follow these steps in order.

### 1. Create the account

- Go to aws.amazon.com and create an account with your email.
- Use a strong, unique password. AWS root account credentials should never be reused elsewhere.

### 2. Secure the root user

- Enable MFA on the root account immediately, before doing anything else. Use an authenticator app, not SMS.
- Never use the root account for daily work after this. It exists only for account-level tasks like billing.

### 3. Create an IAM admin user

- Go to IAM, create a new user for yourself with AdministratorAccess policy attached.
- Enable MFA on this user too.
- Use this user, not root, for all console and CLI work going forward.

### 4. Set a budget alert

- Go to Billing, set up a budget alert at a low threshold (start at $10).
- This catches misconfigured resources before they cost real money.

### 5. Create an IAM role for the application

- Do not use your personal IAM user's credentials in application code, ever.
- Create a separate IAM role or user scoped to only what the app needs: S3 read/write on the specific buckets this project uses, nothing else.
- Generate access keys for this role and store them as environment variables, never committed to git.

### 6. Set up S3 buckets

- Create two buckets: one for transaction receipts, one for exported statements.
- Block all public access on both buckets, by default.
- Enable versioning so accidental overwrites or deletes can be recovered.

### 7. Choose a region

- Pick the AWS region closest to your primary users or closest to you for now (af-south-1, Cape Town, is the closest African region; otherwise eu-west or similar depending on latency needs).
- Use the same region for all resources in this project to avoid cross-region latency and transfer costs.

### 8. Local CLI setup

- Install the AWS CLI.
- Run `aws configure` and enter the access key and secret from your application IAM role, not root or your personal admin user.
- Verify with `aws s3 ls`.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Separate IAM role for the app, scoped to specific S3 buckets only | Principle of least privilege, standard AWS security best practice, limits damage if credentials leak |
| 2026-07-11 | MFA required on root and admin IAM user | Root account compromise is catastrophic and irreversible in some cases, MFA is the baseline defense |

## Open questions

- None yet.
