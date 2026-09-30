# Attribution and project scope

This repository contains a cleaned portfolio copy of a Fall 2025 Computer
Architecture course project by Michelle Yoon Ha Lee.

The course framework is based on the Davis In-Order (DINO) CPU project:

- Project: https://github.com/jlpteaching/dinocpu
- Original copyright: Copyright (c) 2018, The Regents of the University of California
- License: BSD 3-Clause

The student-authored implementation accumulated across three lab milestones:

## Lab 2

- `src/main/scala/components/alucontrol.scala`
- `src/main/scala/single-cycle/cpu.scala` (initial R-type datapath)

## Lab 3

- `src/main/scala/components/control.scala`
- `src/main/scala/components/branch-control.scala`
- `src/main/scala/single-cycle/cpu.scala` (memory and control-flow extension)

## Lab 4

- `src/main/scala/components/forwarding.scala`
- `src/main/scala/components/hazard.scala`
- `src/main/scala/pipelined/cpu.scala`

This corresponds to seven distinct student-implemented source files because
`single-cycle/cpu.scala` was developed across both Labs 2 and 3.

The remaining framework and support files are retained only to preserve build
context. No original authorship is claimed over those supplied components.
