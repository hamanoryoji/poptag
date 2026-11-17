"""Local protocol smoke checks using repository recordings and one labeled synthetic fixture."""
from pathlib import Path
from replay_test import replay
root=Path(__file__).resolve().parent
cases=[
 ("first", "replay_first_cs.bin", {193,2,67,1,51}),
 ("login", "replay_login_cs.bin", {4,11,13,12}),
 ("channel", "replay_enter_cs.bin", {48,13,14,97,122,87,423}),
 ("connect", "replay_connect_cs.bin", {7}),
 ("practice-synthetic", "replay_practice_cs.bin", {87,83,4,113,100,108,106,114,104,105,103,198,423,111,112,136}),
]
for name,filename,ops in cases:
 replay(name,(root/filename).read_bytes(),ops)
print("PASS: all local protocol smoke cases")
