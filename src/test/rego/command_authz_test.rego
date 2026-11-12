package acl.command_authz
test_rpm_high_day_execute_allow { decision.allow with input as {"command":"write","target":{"group":"line1"},"value":1500,"context":{"hour":14,"state":"Execute"}} }
test_rpm_high_night_deny { not decision.allow with input as {"command":"write","target":{"group":"line1"},"value":1500,"context":{"hour":2,"state":"Execute"}} }
test_rpm_high_nonexecute_deny { not decision.allow with input as {"command":"write","target":{"group":"line1"},"value":1500,"context":{"hour":14,"state":"Idle"}} }
test_rpm_normal_allow_any_context { decision.allow with input as {"command":"write","target":{"group":"line1"},"value":800,"context":{"hour":2,"state":"Idle"}} }
test_safehold_execute_allow { decision.allow with input as {"command":"SafeHold","target":{"group":"line1"},"context":{"state":"Execute"}} }
test_safehold_held_deny { not decision.allow with input as {"command":"SafeHold","target":{"group":"line1"},"context":{"state":"Held"}} }
test_unknown_deny { not decision.allow with input as {"command":"reboot","target":{"group":"line1"},"context":{"hour":14,"state":"Execute"}} }
