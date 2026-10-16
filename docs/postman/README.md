# Driver Postman collection

Import `driver.postman_collection.json` and `driver-local.postman_environment.json`, then select **Scheduled Mobility — Local Driver** as the active environment.

Start the backends with `./scripts/run-local.sh`. The default URLs are `http://localhost:8080` (core) and `http://localhost:8081` (location); update the environment if you choose other ports.

## Login and driver setup

1. Set `driverPhone`. Send **Driver login → 1. Request OTP**.
2. Read the OTP from `var/log/core-app.log`, set `driverOtpCode`, then send **2. Verify OTP and save tokens**. Codes expire in five minutes and are single use. `0000` only works if the backend was restarted with `OTP_FIXED_CODE=0000`; request a code first even in that mode.
3. Driver endpoints require the DRIVER role. A new phone initially receives PASSENGER. If necessary, set `adminPhone` to an existing ADMIN account, use the separate **Admin login** requests, then send **Admin — driver onboarding and review → Register a driver** for `driverPhone`. The collection does not create an administrator or grant admin privileges.
4. Log the driver in again (or refresh) after the DRIVER role is granted. Admin and driver tokens are saved separately by response scripts.
5. Send **My driver profile** and the location JWT check. The location check requires its `local` profile.

## Documents and onboarding

Select a local file for `front` in each multipart upload. Card documents need both `front` and `back` images, or one PDF containing both sides as `front` (disable `back` for a PDF). Profile photos must be JPEG or PNG. Each side is limited to 10 MB; file contents are validated. Leave multipart Content-Type to Postman.

Upload NATIONAL_ID, DRIVING_LICENSE and PROFILE_PHOTO, then submit for review. Admin review must approve the personal documents before training. Final approval also needs an assigned vehicle with approved VEHICLE_REGISTRATION. Vehicle documents require `vehicleId`. Set `documentExpiresOn` to the real expiry date.

Successful profile/register/get requests capture IDs and versions. Get the driver or vehicle again before editing to obtain the latest version. Uploads save `documentId`; change it when reviewing a different document. Vehicle assignment captures the active assignment ID when found; otherwise set `assignmentId` from the response. Preview-url requests save `previewUrl` for opening manually.

The admin folders include rejection, suspension and assignment termination. Run requests individually in the appropriate state; do not run the entire collection as a single workflow. Example national IDs, account details and plates are placeholders and must be unique when registering multiple records.

## Retries and tokens

JSON mutations generate a fresh Idempotency-Key for each send. For a retry, set environment variable `idempotencyKey` to the original request's key (visible in the Postman console), retry the identical request, then clear it before a new operation. This is especially important for refresh-token rotation. Multipart requests omit the optional idempotency header.

Refresh responses replace the stored access and refresh tokens. Logout revokes the refresh session. Auth requests intentionally omit bearer authentication, so expired access tokens do not block refresh.

The exported environment contains no tokens or private keys. Login stores tokens in your selected Postman environment; remove them before exporting or sharing it. No live login, driver registration or onboarding mutations were executed while generating these files.
