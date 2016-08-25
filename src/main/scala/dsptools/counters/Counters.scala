//// See LICENSE for license details.
//
//package dsptools.counters
//
//import chisel3.Bundle
//import chisel3.core._
//
///** Ctrl locations:
//  * External = Use external Ctrl signal
//  * Internal = Use interal Ctrl signal (i.e. wrap when maxed out)
//  * TieFalse = Fix Ctrl signal to false
//  * TieTrue = Fix Ctrl signal to true
//  */
//abstract class CtrlLoc
//case object External extends CtrlLoc
//case object Internal extends CtrlLoc
//case object TieFalse extends CtrlLoc
//case object TieTrue extends CtrlLoc
//
///** Count type:
//  * Up = always count up (count + inc)
//  * Down = always count down (count - inc)
//  * UpDown = count up/down (ctrl signal required)
//  * UpMod = always count up, but mod with #
//  */
//abstract class CountType
//case object Up extends CountType
//case object Down extends CountType
//case object UpDown extends CountType
//case object UpMod extends CountType
//
///** Counter Generator parameters */
//case class CountParams (
//                         countMax:   Int,                      // Upper limit of counter range
//                         incMax:     Int       = 1,            // Upper limit of increment range
//                         resetVal:   Int       = 0,            // Value on reset
//                         wrapCtrl:   CtrlLoc   = Internal,     // Location of wrap control signal
//                         changeCtrl: CtrlLoc   = External,     // Location of counter update control signal
//                         countType:  CountType = Up,           // Count type/direction
//                         customWrap: Boolean   = false,        // Whether custom wrap to value exists
//                         inputDelay: Int       = 0             // Keep track of accumulated delay until module inputs
//                       ){
//  require (inputDelay >= 0, "Input delay must be non-negative")
//  require (countMax >= 0, "Max counter value must be non-negative")
//  require (resetVal >= 0 && resetVal <= countMax, "Counter reset should be [0,countMax]")
//  require (incMax > 0 && incMax <= countMax, "Counter increment should be (0,countMax]")
//  require (wrapCtrl != TieTrue, "Can't always wrap")
//  require (changeCtrl == External || changeCtrl == TieTrue, "Either update on external signal or always update")
//  require (!((countType == UpDown || countType == Down) && (incMax > 1) && (!customWrap || wrapCtrl == Internal)),
//    "You must use a custom wrap condition and wrap to value if your counter delta is > 1"
//      + " and you are possibly counting down")
//  require (!(countType == Up && incMax > 1 && wrapCtrl == External && !customWrap),
//    "When using an up counter with increment > 1, an external wrap condition cannot be used to trigger"
//      + " counter to wrap to some __ internally defined value")
//}
//
///** Counter control signals (I --> O can be passed through chain of counters) */
//class CountCtrl (countParams: CountParams) extends Bundle {
//  val wrap = if (countParams.wrapCtrl == External) Some(Bool(INPUT)) else None
//  val change = if (countParams.changeCtrl == External) Some(Bool(INPUT)) else None
//  val reset = Bool(INPUT)
//}
//
///** Counter IO */
//class CountIO (countParams: CountParams) extends Bundle {
//  // Count up/down control signal
//  val upDown = if (countParams.countType == UpDown) Some(Bool(INPUT)) else None
//  // Counters usually increment by 1
//  val inc = if (countParams.incMax != 1) Some(UInt(INPUT,countParams.incMax)) else None
//  // Counter wrap to value (up counters default wrap to 0)
//  val wrapTo =  if (countParams.customWrap) Some(UInt(INPUT,countParams.countMax)) else None
//  // Counter default wrap condition is when count is maxed out (so need to know max)
//  val max = {
//    if (countParams.wrapCtrl == Internal && countParams.countType != UpMod) Some(UInt(INPUT,countParams.countMax))
//    else None
//  }
//  // n in x%n
//  val modN = if (countParams.countType == UpMod) Some(UInt(INPUT,countParams.countMax+1)) else None
//  val out = UInt(OUTPUT,countParams.countMax)
//}
//
///** Counter template */
//abstract class Counter(countParams: CountParams) extends Module {
//
//  val io = new CountIO(countParams)
//
//  val iCtrl = new CountCtrl(countParams)
//  val oCtrl = new CountCtrl(countParams).flip
//
//  val inc = io.inc.getOrElse(UInt(1))
//  val max = io.max.getOrElse(UInt(countParams.countMax))
//
//  val eq0 = (io.out === UInt(0))
//  val eqMax = (io.out === max)
//
//  val (upCustom, upCustomWrap) = Mod(io.out + inc, max + UInt(1))
//  val (modOut,overflow) = {
//    if(io.modN == None) (io.out + inc,Bool(false))
//    else Mod(io.out + inc,io.modN.get)
//  }
//
//  // Adapt wrap condition based off of type of counter if it isn't retrieved externally
//  val wrap = countParams.wrapCtrl match {
//    case Internal => {
//      countParams.countType match {
//        case UpDown => Mux(io.upDown.get, eq0, eqMax)
//        case Down => eq0
//        case Up => {
//          // For >1 increments, custom wrap indicated by sum overflow on next count
//          if (countParams.incMax > 1) upCustomWrap
//          else eqMax
//        }
//        case UpMod => overflow
//      }
//    }
//    case TieFalse => Bool(false)
//    case TieTrue => Bool(true)
//    case External => iCtrl.wrap.get
//  }
//
//  // Adapt wrap to value based off of type of counter if it isn't retrieved externally
//  val wrapTo = {
//    io.wrapTo.getOrElse(
//      countParams.countType match {
//        case UpDown => Mux(io.upDown.get,max, UInt(0))
//        case Down => max
//        case _ => UInt(0)
//      }
//    )
//  }
//
//  // If incrementing by 1 or using external wrap signals, add normally
//  // But if incrementing by >1 and using internal wrap signals, do add mod (max + 1)
//  val up = {
//    if (countParams.incMax == 1 || (countParams.wrapCtrl == External && countParams.customWrap))
//      (io.out + inc).shorten(countParams.countMax)
//    else upCustom
//  }
//
//  val down = io.out - inc
//
//  val nextInSeq = countParams.countType match {
//    case UpDown => Mux(io.upDown.get,down,up)
//    case Up => up
//    case Down => down
//    case UpMod => modOut
//  }
//
//  // When only internal wrap signals are used, note that mods already produce appropriately wrapped counter values
//  val nextCount = {
//    if (countParams.wrapCtrl == Internal && (countParams.countType == UpMod ||
//      (countParams.countType == Up && countParams.incMax > 1 && !countParams.customWrap)))
//      nextInSeq
//    else Mux(wrap,wrapTo,nextInSeq)
//  }
//
//  // Conditionally update (hold until update) or always update
//  val newOnClk = countParams.changeCtrl match {
//    case External => Mux(iCtrl.change.get,nextCount,io.out)
//    case TieTrue => nextCount
//  }
//
//  val count = Mux(iCtrl.reset,UInt(countParams.resetVal),newOnClk)
//  io.out := count.reg()
//
//  // When counters are chained, subsequent counter increments when current counter wraps
//  if (countParams.changeCtrl == External) oCtrl.change.get := wrap & iCtrl.change.get
//  if (countParams.wrapCtrl == External) oCtrl.wrap.get := wrap
//  oCtrl.reset := iCtrl.reset
//
//}
//
//
//class Counters {
//
//}