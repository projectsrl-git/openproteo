# 2026-06-17 — nested / indirect variable resolution

VarResolver.resolve is now iterative and innermost-first, enabling indirection (factory
pattern): ${TargetDestination.${targetId}} resolves the inner ${targetId} first (-> T1),
then ${TargetDestination.T1}. Regex changed to innermost ${[^${}]+} (names can't contain
$ { }); loop up to MAX_DEPTH=12, stops when stable (self-references like a -> ${a} are left
as-is, no infinite loop) or when nothing remains. Single-level behavior unchanged; unknown
names still resolve to "". Applies everywhere resolve() is used, including gate conditions.
Tested (factory pattern, chain a->${b}->X, unknown, self-ref, inner-missing). 76 classes
compile. Documented in USAGE/README.
