# R8 rules for the release build.
#
# Intentionally empty: the app uses no reflection, serialization or native loading, and Compose,
# tv-material and coroutines ship their own consumer rules. Add a keep rule here only for a crash
# seen in a release smoke test, with a comment saying which class and why. A stack trace can be
# deobfuscated with app/build/outputs/mapping/release/mapping.txt.
