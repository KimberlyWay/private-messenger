# Cloud history and login

Starting with 0.5.0, sign in on any device using the same login and password. Messages and attachments load from the server without recovery keys, another phone or a separate account migration flow.

Deploy the updated server gateway and update both Android clients together. The client checks the cloud capability on login and explains when the server needs updating. The database adds a nullable `cloud_room_id` column automatically; old room references and account credentials are preserved. New cloud rooms use separate deterministic aliases, so encrypted rooms are never reused accidentally.

Old encrypted history requires old keys and is not automatically copied. The new history starts in cloud rooms. Existing credentials of both earlier formats work through the same login form.

Cloud history is protected in transit by HTTPS/TLS, and is readable by the server operator. Preserve server volumes and maintain server backups. Reinstalling a phone does not erase history; deleting the server volumes does.
