# RISC-V CPU: Single-Cycle to Five-Stage Pipeline in Chisel

This repository is a cleaned portfolio archive of my Fall 2025 Computer
Architecture project at Seoul National University. For course lab project, I built
the processor incrementally: first the ALU decoder and R-type single-cycle
datapath, then the complete single-cycle control and data paths, and finally a
five-stage pipeline with forwarding and hazard recovery.

**Author:** Michelle Yoon Ha Lee  
**Stack:** Chisel, Scala, RISC-V, sbt

## What I implemented

The source below is the cumulative version from my final course archive. The
same `single-cycle/cpu.scala` file was first built for R-type execution and then extended for memory and control-flow instructions.

### ALU decoding and R-type single-cycle CPU

| File | My contribution |
| --- | --- |
| [`components/alucontrol.scala`](src/main/scala/components/alucontrol.scala) | Decoded `funct3` and `funct7` into ALU operations, including ADD/SUB and SRL/SRA disambiguation, with forced-add and immediate modes for later instruction types. |
| [`single-cycle/cpu.scala`](src/main/scala/single-cycle/cpu.scala) | Connected instruction fields, register reads, ALU control, ALU execution, register write-back, `x0` write protection, and sequential `PC + 4` execution for R-type instructions. |

### Complete single-cycle control and datapath

| File | My contribution |
| --- | --- |
| [`components/control.scala`](src/main/scala/components/control.scala) | Built an opcode-to-control table for R-type, I-type, load, store, branch, LUI, AUIPC, JAL, and JALR instructions, generating nine datapath control signals. |
| [`components/branch-control.scala`](src/main/scala/components/branch-control.scala) | Implemented BEQ/BNE and signed or unsigned BLT/BGE comparisons from `funct3`, gated by the main branch control signal. |
| [`single-cycle/cpu.scala`](src/main/scala/single-cycle/cpu.scala) | Extended the R-type design with immediate generation, data-memory access, ALU/memory/`PC + 4` write-back selection, and prioritized next-PC logic for branches, JAL, and JALR. |

### Five-stage pipelined CPU

| File | My contribution |
| --- | --- |
| [`components/forwarding.scala`](src/main/scala/components/forwarding.scala) | Compared EX-stage sources against EX/MEM and MEM/WB destinations, excluded `x0`, and prioritized the newer EX/MEM value. |
| [`components/hazard.scala`](src/main/scala/components/hazard.scala) | Detected load-use dependencies, held the PC and IF/ID register, inserted an ID/EX bubble, and flushed younger instructions after a taken branch or jump. |
| [`pipelined/cpu.scala`](src/main/scala/pipelined/cpu.scala) | Reorganized the single-cycle processor into IF-ID-EX-MEM-WB, defined four pipeline-register bundles, propagated stage-specific controls, and integrated forwarding, stalls, bubbles, and control-hazard recovery. |

## Pipelined design

```mermaid
flowchart LR
    IF["IF<br/>Fetch"] -->|"IF/ID"| ID["ID<br/>Decode"]
    ID -->|"ID/EX"| EX["EX<br/>Execute"]
    EX -->|"EX/MEM"| MEM["MEM<br/>Memory"]
    MEM -->|"MEM/WB"| WB["WB<br/>Write-back"]
```

- **Pipeline state:** IF/ID stores the instruction and PC; ID/EX carries
  operands, immediates, register identifiers, and grouped EX/MEM/WB control
  signals; EX/MEM and MEM/WB carry results and the remaining controls.
- **Data forwarding:** each ALU operand can come from ID/EX, EX/MEM, or the
  final write-back value. Store data uses the forwarded `rs2` path as well.
- **Load-use hazard:** if a load in ID/EX produces an operand needed by the
  instruction in ID, the PC and IF/ID register are held for one cycle while a
  NOP is inserted into ID/EX.
- **Control hazard:** the pipeline predicts not taken. Branch and jump targets
  are computed in EX and recorded in EX/MEM; a taken result redirects the PC
  and clears younger wrong-path instructions.

## Verification

The original lab reports preserve the following passing results:

| Milestone | Verified behavior | Result |
| --- | --- | ---: |
| Lab 2 | ALU control, R-type instructions, multi-instruction R-type programs | **24/24 passed** |
| Lab 3 | I/U-type, loads/stores, signed and unsigned branches, application programs | **51/51 passed** |
| Lab 4 | Pipelined integer, memory, branch, jump, dependency, and application tests | **75/75 passed** |

These are milestone-specific counts and include overlapping instruction cases
as the processor grew. See [the original terminal captures and suite
breakdown](docs/verification.md).

## Repository layout

```text
src/main/scala/
├── components/
│   ├── alucontrol.scala         # My Lab 2 implementation
│   ├── control.scala            # My Lab 3 implementation
│   ├── branch-control.scala     # My Lab 3 implementation
│   ├── forwarding.scala        # My Lab 4 implementation
│   ├── hazard.scala            # My Lab 4 implementation
│   └── ...                     # Supplied support components
├── single-cycle/cpu.scala       # My Lab 2-3 implementation
├── pipelined/cpu.scala          # My Lab 4 implementation
└── testing/                     # Supplied simulation support
docs/
├── verification.md
├── lab2-test-results.png
├── lab3-test-results.png
└── lab4-test-results.png
build.sbt
project/
```

## Build and elaborate

The supplied course build targets Scala 2.12.10, sbt 1.2.7, and Chisel 3.2.x.
With a compatible JDK and sbt installation:

```bash
sbt compile
sbt "runMain dinocpu.elaborate pipelined"
```

Elaboration produces FIRRTL and Verilog output for the pipelined design.

## Scope and attribution

This project was completed within a supplied course framework based on the
open-source [Davis In-Order (DINO) CPU](https://github.com/jlpteaching/dinocpu).
The framework, base support components, build configuration, simulator, and
test infrastructure were provided and are not claimed as my original work.
My implementation spans the seven distinct source files identified above.

All Scala source files in this portfolio copy remain byte-for-byte identical
to the cumulative project archive. Only the README, attribution, verification
images, and ignore rules were added for presentation.

The upstream DINO CPU is distributed under the BSD 3-Clause License. See
[`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md).
