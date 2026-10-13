# Production Deployment Runbook

Internal engineering runbook. Synthetic demo content.

## Change Freeze

Production deployments are frozen on Fridays after 14:00 and during public holidays.

## Approvals

Every production deployment requires two approvals in the change management tool, one of which must come from a senior engineer.

## Rollback

To roll back a release, run the deployctl rollback command with the release identifier. Rollbacks do not require additional approval.
