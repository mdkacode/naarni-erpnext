"""Push Token DocType.

Stores device push tokens (FCM/APNs/Expo) per user so the notification engine can
deliver push messages to a user's App installs. Tokens are registered via
`vehicle_maintenance.api.notifications.register_push_token`.
"""

from frappe.model.document import Document


class PushToken(Document):
	pass
