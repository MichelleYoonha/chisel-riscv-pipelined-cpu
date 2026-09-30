// This file is where all of the CPU components are assembled into the whole CPU

package dinocpu

import chisel3._
import chisel3.util._

/**
 * The main CPU definition that hooks up all of the other components.
 *
 * For more information, see section 4.6 of Patterson and Hennessy
 * This follows figure 4.49
 */
class PipelinedCPU(implicit val conf: CPUConfig) extends Module {
  val io = IO(new CoreIO)

  /////////////////////////////////////////////////////////////////////////////
  // Bundles defining the pipeline registers and control structures
  /////////////////////////////////////////////////////////////////////////////

  // Everything in the register between IF and ID stages
  class IFIDBundle extends Bundle {
    val instruction = UInt(32.W)
    val pc          = UInt(32.W)
    val pcplusfour  = UInt(32.W)
  }

  // Control signals used in EX stage
  class EXControl extends Bundle {
    val add       = Bool()
    val immediate = Bool()
    val alusrc1   = UInt(2.W)
    val branch    = Bool()
    val jump      = UInt(2.W)
  }

  // Control signals used in MEM stage
  class MControl extends Bundle {
    val memread   = Bool()
    val memwrite  = Bool()
    val maskmode  = UInt(2.W)
    val sext      = Bool()
  }

  // Control signals used in WB stage
  class WBControl extends Bundle {
    val regwrite  = Bool()
    val toreg     = UInt(2.W)
  }

  // Everything in the register between ID and EX stages
  class IDEXBundle extends Bundle {
    // data
    val pc         = UInt(32.W)
    val pcplusfour = UInt(32.W)

    val rs1        = UInt(5.W)
    val rs2        = UInt(5.W)
    val rd         = UInt(5.W)

    val funct3     = UInt(3.W)
    val funct7     = UInt(7.W)

    val readdata1  = UInt(32.W)
    val readdata2  = UInt(32.W)
    val imm        = UInt(32.W)

    // controls
    val excontrol = new EXControl
    val mcontrol  = new MControl
    val wbcontrol = new WBControl
  }

  // Everything in the register between EX and MEM stages
  class EXMEMBundle extends Bundle {
    // data
    val pcplusfour  = UInt(32.W)
    val rd          = UInt(5.W)

    val aluresult   = UInt(32.W)
    val writedata   = UInt(32.W) // for stores

    // branch-related (Part 3에서 PC/flush에 사용)
    val branchTarget = UInt(32.W)
    val taken        = Bool()

    // controls
    val mcontrol  = new MControl
    val wbcontrol = new WBControl
  }

  // Everything in the register between MEM and WB stages
  class MEMWBBundle extends Bundle {
    // data
    val pcplusfour = UInt(32.W)
    val rd         = UInt(5.W)

    val aluresult  = UInt(32.W)
    val memdata    = UInt(32.W)

    // controls
    val wbcontrol = new WBControl
  }

  /////////////////////////////////////////////////////////////////////////////
  // All of the structures required
  /////////////////////////////////////////////////////////////////////////////

  val pc         = RegInit(0.U(32.W))
  val control    = Module(new Control())
  val branchCtrl = Module(new BranchControl())
  val registers  = Module(new RegisterFile())
  val aluControl = Module(new ALUControl())
  val alu        = Module(new ALU())
  val immGen     = Module(new ImmediateGenerator())
  val pcPlusFour = Module(new Adder())
  val branchAdd  = Module(new Adder())
  val forwarding = Module(new ForwardingUnit())  // pipelined only (Part1에서는 사용 X)
  val hazard     = Module(new HazardUnit())      // pipelined only (Part1에서는 사용 X)

  val (cycleCount, _) = Counter(true.B, 1 << 30)

  val if_id  = RegInit(0.U.asTypeOf(new IFIDBundle))
  val id_ex  = RegInit(0.U.asTypeOf(new IDEXBundle))
  val ex_mem = RegInit(0.U.asTypeOf(new EXMEMBundle))
  val mem_wb = RegInit(0.U.asTypeOf(new MEMWBBundle))


  printf("Cycle=%d ", cycleCount)

  // Forward declaration of wires that connect different stages

  // From memory back to fetch. Since we don't decide whether to take a branch or not until the memory stage.
  val next_pc = Wire(UInt(32.W))

  // For wb back to other stages
  val write_data = Wire(UInt(32.W))

  /////////////////////////////////////////////////////////////////////////////
  // FETCH STAGE
  /////////////////////////////////////////////////////////////////////////////
  
  // Note: This comes from the memory stage!
  // Only update the pc if the pcwrite flag is enabled

  val pc_fetch = pc

  // Send the PC to instruction memory port to get the instruction
  io.imem.address := pc_fetch
  
  // Get the PC + 4
  pcPlusFour.io.inputx := pc_fetch
  pcPlusFour.io.inputy := 4.U

  // hazard
  next_pc := pcPlusFour.io.result
  
  switch(hazard.io.pcwrite) {
    is(0.U) { next_pc := pcPlusFour.io.result}
    is(1.U) { next_pc := ex_mem.branchTarget }
    is(2.U) { next_pc := pc_fetch } // stall
  }
  
  pc := next_pc

  // Fill the IF/ID register if we are not bubbling IF/ID
  // otherwise, leave the IF/ID register *unchanged*
  
  when (hazard.io.ifid_flush) {
    if_id := 0.U.asTypeOf(new IFIDBundle)   // flush = set to 0
  } .elsewhen (!hazard.io.ifid_bubble) {
    if_id.instruction := io.imem.instruction
    if_id.pc          := pc_fetch
    if_id.pcplusfour  := pcPlusFour.io.result
    } .otherwise {
   // stall , bubble
   }

  printf(p"IF/ID: $if_id\n")

  /////////////////////////////////////////////////////////////////////////////
  // ID STAGE
  /////////////////////////////////////////////////////////////////////////////

  val rs1 = if_id.instruction(19, 15)
  val rs2 = if_id.instruction(24, 20)
  val rd = if_id.instruction(11, 7)

  // Send input from this stage to hazard detection unit
  hazard.io.rs1 := rs1
  hazard.io.rs2 := rs2
  hazard.io.idex_memread := id_ex.mcontrol.memread
  hazard.io.idex_rd:= id_ex.rd
  hazard.io.exmem_taken := ex_mem.taken

  // Send opcode to control
  control.io.opcode := if_id.instruction(6, 0)

  // Send register numbers to the register file
  registers.io.readreg1 := rs1
  registers.io.readreg2 := rs2

  // Send the instruction to the immediate generator
  immGen.io.instruction := if_id.instruction

  // FIll the id_ex register

  when (hazard.io.idex_bubble) {
  id_ex := 0.U.asTypeOf(new IDEXBundle)   // NOP
  } .otherwise {
  
  id_ex.pc := if_id.pc
  id_ex.pcplusfour := if_id.pcplusfour

  id_ex.rs1 := rs1
  id_ex.rs2 := rs2
  id_ex.rd := rd

  id_ex.funct3 := if_id.instruction(14, 12)
  id_ex.funct7 := if_id.instruction(31, 25)

  id_ex.readdata1 := registers.io.readdata1
  id_ex.readdata2 := registers.io.readdata2
  id_ex.imm := immGen.io.sextImm

  // Set the execution control signals
  id_ex.excontrol.add := control.io.add
  id_ex.excontrol.immediate := control.io.immediate
  id_ex.excontrol.alusrc1 := control.io.alusrc1
  id_ex.excontrol.branch := control.io.branch
  id_ex.excontrol.jump := control.io.jump
  
  // Set the memory control signals
  val funct3_id = if_id.instruction(14,12)
  val maskmode_id = Wire(UInt(2.W))
  val sext_id     = Wire(Bool())
  
  maskmode_id := 2.U
  sext_id := false.B
  
  when (control.io.memread) {
    switch(funct3_id) {
    is("b000".U) { maskmode_id := 0.U; sext_id := true.B  } // lb
    is("b001".U) { maskmode_id := 1.U; sext_id := true.B  } // lh
    is("b010".U) { maskmode_id := 2.U; sext_id := false.B } // lw
    is("b100".U) { maskmode_id := 0.U; sext_id := false.B } // lbu
    is("b101".U) { maskmode_id := 1.U; sext_id := false.B } // lhu
    }
  } .elsewhen (control.io.memwrite) {
  sext_id := false.B
  switch(funct3_id) {
    is("b000".U) { maskmode_id := 0.U } // sb
    is("b001".U) { maskmode_id := 1.U } // sh
    is("b010".U) { maskmode_id := 2.U } // sw
  }
}

  id_ex.mcontrol.memread := control.io.memread
  id_ex.mcontrol.memwrite := control.io.memwrite
  id_ex.mcontrol.maskmode := maskmode_id
  id_ex.mcontrol.sext := sext_id

  // Set the writeback control signals
  id_ex.wbcontrol.regwrite  := control.io.regwrite
  id_ex.wbcontrol.toreg := control.io.toreg
}


  printf("DASM(%x)\n", if_id.instruction)
  printf(p"ID/EX: $id_ex\n")

  /////////////////////////////////////////////////////////////////////////////
  // EX STAGE
  /////////////////////////////////////////////////////////////////////////////

  // Set the input to the forwarding unit from this stage (SKIP FOR PART I)
  forwarding.io.rs1     := id_ex.rs1
  forwarding.io.rs2     := id_ex.rs2
  forwarding.io.exmemrd := ex_mem.rd
  forwarding.io.exmemrw := ex_mem.wbcontrol.regwrite
  forwarding.io.memwbrd := mem_wb.rd
  forwarding.io.memwbrw := mem_wb.wbcontrol.regwrite

  // Connect the ALU control wires (line 45 of single-cycle/cpu.scala)
  aluControl.io.add := id_ex.excontrol.add
  aluControl.io.immediate := id_ex.excontrol.immediate
  aluControl.io.funct7 := id_ex.funct7
  aluControl.io.funct3 := id_ex.funct3

  // Insert the forward inputx mux here (SKIP FOR PART I)
  val fwd_rs1 = Wire(UInt(32.W))
  val fwd_rs2 = Wire(UInt(32.W))
  fwd_rs1 := MuxLookup(forwarding.io.forwardA, id_ex.readdata1, Array(
    0.U -> id_ex.readdata1,
    1.U -> ex_mem.aluresult, // (EX/MEM)
    2.U -> write_data // MEM/WB
  ))
  
  
  // Insert the ALU inpux mux here (line 59 of single-cycle/cpu.scala)
  val alu_in_x = Wire(UInt(32.W))
  alu_in_x := fwd_rs1
  switch(id_ex.excontrol.alusrc1) {
    is(0.U) { alu_in_x := fwd_rs1 }
    is(1.U) { alu_in_x := 0.U }
    is(2.U) { alu_in_x := id_ex.pc }
  }
  
  // Insert forward inputy mux here (SKIP FOR PART I)
  fwd_rs2 := MuxLookup(forwarding.io.forwardB, id_ex.readdata2, Array(
    0.U -> id_ex.readdata2,
    1.U -> ex_mem.aluresult,
    2.U -> write_data
  ))

  // Input y mux (line 66 of single-cycle/cpu.scala)
  val alu_in_y = Wire(UInt(32.W))
  alu_in_y := Mux(id_ex.excontrol.immediate, id_ex.imm, fwd_rs2)

  // Connect the branch control wire (line 54 of single-cycle/cpu.scala)
  branchCtrl.io.branch := id_ex.excontrol.branch
  branchCtrl.io.funct3 := id_ex.funct3
  branchCtrl.io.inputx := fwd_rs1
  branchCtrl.io.inputy := fwd_rs2

  // Set the ALU operation
  alu.io.inputx := alu_in_x
  alu.io.inputy := alu_in_y
  alu.io.operation := aluControl.io.operation

  // Connect the branchAdd unit
  branchAdd.io.inputx := id_ex.pc
  branchAdd.io.inputy := id_ex.imm

  val isJump = id_ex.excontrol.jump =/= 0.U
  val jalTarget  = branchAdd.io.result
  val jalrTarget = (fwd_rs1 + id_ex.imm)
  
  val jumpTarget = Wire(UInt(32.W))
  jumpTarget := jalTarget
  
  when(id_ex.excontrol.jump === 3.U) { // jalr
  jumpTarget := jalrTarget
  }
  val taken_ex = branchCtrl.io.taken || isJump
  val target_ex = Mux(isJump, jumpTarget, branchAdd.io.result)
  

  // Set the EX/MEM register values
when (hazard.io.exmem_bubble) {
  ex_mem := 0.U.asTypeOf(new EXMEMBundle)
} .otherwise {
  ex_mem.pcplusfour   := id_ex.pcplusfour
  ex_mem.rd           := id_ex.rd
  ex_mem.aluresult    := alu.io.result
  ex_mem.writedata    := fwd_rs2

  ex_mem.branchTarget := target_ex
  ex_mem.taken        := taken_ex

  ex_mem.mcontrol     := id_ex.mcontrol
  ex_mem.wbcontrol    := id_ex.wbcontrol
}

  printf(p"EX/MEM: $ex_mem\n")

  /////////////////////////////////////////////////////////////////////////////
  // MEM STAGE
  /////////////////////////////////////////////////////////////////////////////

  // Set data memory IO (line 71 of single-cycle/cpu.scala)
  io.dmem.address := ex_mem.aluresult
  io.dmem.writedata := ex_mem.writedata
  io.dmem.memread := ex_mem.mcontrol.memread
  io.dmem.memwrite := ex_mem.mcontrol.memwrite
  io.dmem.maskmode := ex_mem.mcontrol.maskmode
  io.dmem.sext := ex_mem.mcontrol.sext

  // Send next_pc back to the fetch stage -> 위에
  
  // Send input signals to the hazard detection unit (SKIP FOR PART I) -> 위에

  // Send input signals to the forwarding unit (SKIP FOR PART I) -> 위에

  // Wire the MEM/WB register
  mem_wb.pcplusfour := ex_mem.pcplusfour
  mem_wb.rd := ex_mem.rd
  mem_wb.aluresult := ex_mem.aluresult
  mem_wb.memdata := io.dmem.readdata
  mem_wb.wbcontrol:= ex_mem.wbcontrol

  printf(p"MEM/WB: $mem_wb\n")

  /////////////////////////////////////////////////////////////////////////////
  // WB STAGE
  /////////////////////////////////////////////////////////////////////////////

  // Set the writeback data mux (line 78 single-cycle/cpu.scala)
  write_data := MuxLookup(mem_wb.wbcontrol.toreg, mem_wb.aluresult, Array(
  0.U -> mem_wb.aluresult,
  1.U -> mem_wb.memdata,
  2.U -> mem_wb.pcplusfour ))

  // Write the data to the register file
  registers.io.writereg := mem_wb.rd
  registers.io.writedata := write_data
  registers.io.wen := mem_wb.wbcontrol.regwrite && (mem_wb.rd =/= 0.U)
  
  // Set the input signals for the forwarding unit (SKIP FOR PART I) -> 위에

  printf("---------------------------------------------\n")
}
