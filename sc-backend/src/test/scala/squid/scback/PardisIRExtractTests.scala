package squid
package scback

import ch.epfl.data.sc._
import pardis.deep.scalalib.ScalaPredefIRs.Println
import squid.ir.RewriteAbort
import utils._
import squid.ir.{FixPointRuleBasedTransformer, SimpleRuleBasedTransformer, TopDownTransformer, FixPointTransformer}

import collection.mutable.ArrayBuffer
import scala.collection.mutable

class PardisIRExtractTests extends PardisTestSuite {
  
  import Sqd.Predef._
  import Sqd.Quasicodes._
  
  
  /*_*/
  
  
  
  test("Constants") {
    
    val q = ir{42}
    
    q match {
      case ir"${Const(x)}" =>
        x ofType[Int]()
    }
    
    q matches {
      case ir"42" =>
    } and {
      case ir"${Const(x)}:Double" => 
        x ofType[Double]()
        fail
      case ir"${Const(x)}:Int" =>
        x ofType[Int]()
        assert(x == 42)
    } and {
      case ir"$x:Double" => fail
      case ir"$x:Nothing" => fail
      case ir"$x:Int" =>
        assert(x == Const(42))
    }
    
  }
  
  
  
  test("Methods") {
    // TODO impl proper extraction
    
    //block(ir{ArrayBuffer(1,2,3)}) match {
    //  case ir"ArrayBuffer(1,2,3)" =>
    //}
    
  }
  
  
  
  test("Rewriting Consecutive Statements") {
    
    assert(SC.OptionApplyObject(null)(SC.typeInt).isPure) // makes sure we have the right SC version
    
    
    // Specializes Seq constructions to ArrayBuffer
    object Tr extends SimpleRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      rewrite {
        case ir"($arr: ArrayBuffer[Int]) append 42" => ir{ println("nope!") }
        case ir"($arr: ArrayBuffer[$t]) append $x; (arr:ArrayBuffer[t]).clear" => ir{ $(arr).clear }
          
        //case ir"val s = ($arr: ArrayBuffer[$t]).size; (arr:ArrayBuffer[t]) append $x; s" => ir{ $(arr) append $(x); $(arr).size-1 }
        // ^ Note, as expected (because of $x):
        //     Error:(71, 95) Cannot rewrite a term of context <context @ 71:14> to a stricter context <context @ 71:14>{val s: Int}
          
        case ir"val s = ($arr: ArrayBuffer[$t]).size; (arr:ArrayBuffer[t]) append $x; s" =>
          val x2 = x subs 's -> ((throw new RewriteAbort): IR[Int,{}])
          ir{ $(arr) append $(x2); $(arr).size-1 }
          
      }}
    
    sameDefs(ir{ val arr = ArrayBuffer(1,2,3); arr append 42;    arr.size } transformWith Tr,
             ir{ val arr = ArrayBuffer(1,2,3); println("nope!"); arr.size })
    
    sameDefsAfter(
             ir{ val arr = ArrayBuffer(1,2,3); arr append 43;    arr.size }, _ transformWith Tr)
    
    // Tries to rm pure stmts referring to rm'd syms (trans clos):
    sameDefs(ir{ val arr = new ArrayBuffer[Int](); Option(arr append 1); arr.clear; arr.size } transformWith Tr,
             ir{ val arr = new ArrayBuffer[Int]();                       arr.clear; arr.size })
    
    sameDefsAfter( // should not apply (cf later usage of `lol`; can't be removed):
             ir{val arr = new ArrayBuffer[Int](); val lol = arr append 1; arr.clear; println(lol); arr.size}, _ transformWith Tr)
    
    // Update the Option to apply on the new `clear` statement!
    sameDefs(ir{ val arr = new ArrayBuffer[Int](); arr append 1; val cl = arr.clear; Option(cl) } transformWith Tr,
             ir{ val arr = new ArrayBuffer[Int]();               val c  = arr.clear; Option(c)  })
    
    
    sameDefsAfter(ir{
      val arr = new ArrayBuffer[Int]()
      arr append 2
      arr.size  // viewed as effectful; in fact it could be removed (needs a better effect system)
      arr.clear
      42
    }, _ transformWith Tr)
    
    sameDefs(ir{
      val arr = new ArrayBuffer[Int]()
      arr append 2
      val s = arr.size
      val o = Option(s)
      arr append o.get
      arr.clear
      o
    } transformWith Tr, ir{
      val arr = new ArrayBuffer[Int]()
      arr append 2
      val s = arr.size
      val o = Option(s)
      o.get
      arr.clear
      o
    })
    
    
    // Testing the "size-commuting" rewriting
    val y0 =     ir{ val arr = new ArrayBuffer[Int](); val s = arr.size; arr append 1;                                 println(s)  } transformWith Tr
    sameDefs(y0, ir{ val arr = new ArrayBuffer[Int]();                   arr append 1; val s = arr.size; val s0 = s-1; println(s0) })
    stmts(y0) |> {
      case (s @ SC.Stm(a0, d)) :: _ :: SC.Stm(r0, SC.ArrayBufferSize(a1)) :: SC.Stm(s0, SC.`Int-1`(r1, t0)) :: SC.Stm(_, Println(s1)) :: Nil =>
        assert(s.typeT == SC.ArrayBufferType(SC.typeInt))
        assert(a0 == a1) 
        assert(r0 == r1) 
        assert(s0 == s1) 
      case x => fail(x.toString)  // Very fun (huge) warnings sometimes when removing this line!
    }
    
    
    // More complicated, with nested blocks:
    sameDefs(ir{
      if (42.toDouble < 43) {
        println("hey")
        val arr = new ArrayBuffer[Int]()
        Option(arr append 1)
        arr.clear
      } else println("yo")
    } transformWith Tr, ir{
      if (42.toDouble < 43) {
        println("hey")
        val arr = new ArrayBuffer[Int]()
        arr.clear
      } else println("yo")
    })
    
    
    
    import pardis.shallow.scalalib.collection.Cont
    
    object Tr2 extends SimpleRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      rewrite {
        case ir"val s = Seq[$t]($xs*); s" =>
          ir{ ArrayBuffer($(xs:_*)) }
          //ir"ArrayBuffer($xs*)"       // other way, with QQs
      }}
    
    val a0 = ir{ val s = Seq(1,2,3);         println(s(0)); s.size }
    val ar = ir{ val s = ArrayBuffer(1,2,3); println(s(0)); s.size }
    val a1 = a0 transformWith Tr2
    sameDefs(a1, ar)
    
    
    
    object Tr3 extends SimpleRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      rewrite {
        case ir"val a = new Cont[Double]($n, null); new Cont[Double](n, a)" =>  // works as expected
          ir{ new Cont($(n), null) }
        case ir"val a = new Cont[Int]($n, null); val b = new Cont[Int](n, a); a" =>
          ir{ new Cont($(n)+1, null) }
      }}
    
    sameDefs(ir{ new Cont(42.0, new Cont(42.0, null)) } transformWith Tr3,
             ir{ new Cont(42.0, null) } transformWith Tr3)
    
    sameDefsAfter(ir{ new Cont(42, new Cont(42, null)) }, _ transformWith Tr3)
    
    sameDefs(ir{ val inner = new Cont(42, null); new Cont(42, inner); inner } transformWith Tr3,
             ir{ new Cont((42:Int)+1, null) } transformWith Tr3)
    
    sameDefs(ir{ val inner = new Cont(42, null); new Cont(42, inner); inner.elem } transformWith Tr3,
             ir{ new Cont((42:Int)+1, null).elem } transformWith Tr3)
    
    
  }
  
  
  
  test("Rewriting Bindings") {
    
    
    // Specializes Seq constructions to ArrayBuffer
    object Tr extends SimpleRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      rewrite { case ir"val x = Seq[$t]($xs*); $body: $bt" =>  // println(s"Running rwr code!! body = $body")
          ir"val x = ArrayBuffer($xs*); $body"
      }}
    
    
    val a0 = ir{ val s = Seq(1,2,3);         println(s(0)); s.size }
    val ar = ir{ val s = ArrayBuffer(1,2,3); println(s(0)); s.size }
    val a1 = a0 transformWith Tr
    
    sameDefs(a1, ar)
    
    // Verify that the bindings are correctly re-wired, and that the types are right:
    stmts_ret(a1) match {
      case (liftedSeqShit :: SC.Stm(s0,abap:SC.ArrayBufferApplyObject[_]) :: _ :: _ :: SC.Stm(s2,SC.ArrayBufferSize(s1)) :: Nil) -> (s3:Sqd.Sym) =>
        assert(s0 == s1)
        assert(s2 == s3)
        assert(s0.tp == irTypeOf[ArrayBuffer[Int]].rep)
        assert(abap.typeA == irTypeOf[Int].rep)
    }
    
    
    // Another way to do the same thing (but note that it uses a FixedPointTransformer!):
    sameDefs(a0 rewrite { case ir"val x = Seq[$t]($xs*); $body: $bt"  =>  ir"val x = ArrayBuffer($xs*); $body" }, ar)
    
    
    sameDefs(ir{         Seq(1,2,3).filter(_ > 0) } transformWith Tr,
             ir{ ArrayBuffer(1,2,3).filter(_ > 0) })
    
    
    // Note: this transfo used to reorder the stmts (the pure lambda statement was ignored and then reintroduced at the beginning)
    sameDefs(ir{ val s = Seq(1,2,3);         val f: Int => Bool = _ > 0; s.filter(f) } transformWith Tr,
             ir{ val a = ArrayBuffer(1,2,3); val f: Int => Bool = _ > 0; a.filter(f) })
    
    sameDefs(ir{ val s =         Seq(1,2,3); val f: Int => Bool = _ > s.size; s.filter(f) } transformWith Tr,
             ir{ val a = ArrayBuffer(1,2,3); val f: Int => Bool = _ > a.size; a.filter(f) })
    
    sameDefs(ir{ Seq(1,2,3)        .map(_ + 1)                        } transformWith Tr, 
             ir{ ArrayBuffer(1,2,3).map(_ + 1)(Seq.canBuildFrom[Int]) })
    
    
    
    object Tr2 extends SimpleRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      // Short-cuts anything that happens after println(666), if the final type is Unit:
      rewrite { case ir"println(666); $body: Unit" =>  // println(s"Running rwr code!! body = $body")
          ir"println(42)"
      }}
    
    val b0 = ir{
      println(1)
      println(666)
      println(2)
      println(3)
    }
    val b1 = ir{ println(1); println(42) }
    sameDefs(b0 transformWith Tr2, b1)
    stmts_ret(b1) match { case (_ :: SC.Stm(s0, _) :: Nil) -> s1 => assert(s0 == s1) }
    
    sameDefs(ir{
      println(1)
      println(666)
    } transformWith Tr2, b1)
    
    
    // The RwR used to trigger for the following, because it used to interpret the `println(666)` Def as a block
    // with a single statement by introducing a new freshVar. However, this was dubious behavior, so it no longer works. 
    sameDefsAfter(ir{
      println(666)
      "ok"
    }, _ transformWith Tr2)
    
    // currently, if the `body` hole cannot match the _entire_ block remainder (here there is a type mismatch), we don't get binding-rw
    sameDefsAfter(ir{
      println(666)
      println(1)
      "ok"
    }, _ transformWith Tr2)
    
    
  }
  
  
  
  test("FixPoint Compounded Rewriting (sequences and bindings)") {
    
    object Tr extends FixPointRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      rewrite {
        case ir"($arr: ArrayBuffer[$t]) append $x; (arr:ArrayBuffer[t]).clear" =>  // println(s"Running rwr code!! body = $body")
          ir{ $(arr).clear }
        case ir"val arr = new ArrayBuffer[$t](); arr.clear; $body: $bt" =>
          ir{ val arr = new ArrayBuffer[t.Typ](); $(body) }
      }
    }
    
    // should not apply
    sameDefsAfter( ir{
      val arr = ArrayBuffer(1,2,3)
      arr append 1
      arr.size
    }, _ transformWith Tr )
    
    // should remove the option
    sameDefs( ir{
      val arr = new ArrayBuffer[Int]()
      Option(arr append 1)
      arr.clear
      arr.size
    } transformWith Tr, ir{
      val arr = new ArrayBuffer[Int]()
      arr.size
    })
    
    // should keep the option (used later by effectful expr)
    sameDefs( ir{
      val arr = new ArrayBuffer[Int]()
      val a1 = arr(1)
      arr append 1
      val opt = Option(a1)
      arr.clear
      println(opt)
    } transformWith Tr, ir{
      val arr = new ArrayBuffer[Int]()
      val a1 = arr(1)
      val opt = Option(a1)
      arr.clear
      println(opt)
    })
    
    // should keep the option (returned)
    sameDefs( ir{
      val arr = new ArrayBuffer[Int]()
      val a1 = arr(1)
      arr append 1
      val opt = Option(a1)
      arr.clear
      opt
    } transformWith Tr, ir{
      val arr = new ArrayBuffer[Int]()
      val a1 = arr(1)
      val opt = Option(a1)
      arr.clear
      opt
    })
    
    // should not apply (Option returned)
    sameDefsAfter( ir{
      val arr = new ArrayBuffer[Int]()
      val opt = Option(arr append 1)
      arr.clear
      opt
    }, _ transformWith Tr )
    
    // should not apply
    sameDefsAfter( ir{
      val arr = new ArrayBuffer[Int]()
      val lol = arr append 1
      arr.clear
      println(lol)
      arr.size
    }, _ transformWith Tr )
    
    // should not remove the `clear` cf returned
    val a0 = ir{
      val arr = new ArrayBuffer[Int]()
      arr.clear
    }
    sameDefsAfter( a0, _ transformWith Tr )
    
    // Should apply the first rewrite and update the Option, but keep the clear (don't apply second rewrite)
    sameDefs(ir{ val arr = new ArrayBuffer[Int](); arr append 1; val cl = arr.clear; Option(cl) } transformWith Tr,
             ir{ val arr = new ArrayBuffer[Int]();               val c  = arr.clear; Option(c)  })
    
    sameDefs( ir{
      val arr = new ArrayBuffer[Int]()
      arr append 1
      arr append 2
      arr append 3
      arr.clear
    } transformWith Tr, a0)
    
    sameDefs( ir{
      val arr = new ArrayBuffer[Int]()
      arr append 1
      arr.clear
      arr append 2
    } transformWith Tr, ir{
      val arr = new ArrayBuffer[Int]()
      arr append 2
    })
    
    // should apply both (but keep one append)
    sameDefs( ir{
      val arr = new ArrayBuffer[Int]()
      arr append 1
      arr append 2
      arr.clear
      arr append 3
    } transformWith Tr, ir{
      val arr = new ArrayBuffer[Int]()
      arr append 3
    })
    
    // should apply several times
    sameDefs( ir{
      val arr = new ArrayBuffer[Int]()
      arr append 1
      arr.clear
      arr append 2
      arr.clear
      arr append 3
      arr.clear
      arr.size
    } transformWith Tr, ir{
      new ArrayBuffer[Int]().size
    })
    
    // TODO test rwr inside nested block... eg ITE
    
  }
  
  
  
  test("Hole in Statement Position") {
    
    val pgrm = ir"ArrayBuffer[Int]().size"
    sameDefs( pgrm rewrite {
      case ir"val hm: ArrayBuffer[Int] = $init; $body: Int"
      if stmts(body).headOption exists (_.rhs.nodeName != "ArrayBufferApply") =>  // prevents non-convergence of the RwR
        ir"$init(0)"
    }, ir"ArrayBuffer[Int]()(0)")
    
  }
  
  
  
  test("Hole in Pure Statement") {
    
    // TODO
    
    
  }
  
  
  
  test("Extracted Binders") {
    
    object Tr extends FixPointRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      rewrite {
        case ir"val $arr: ArrayBuffer[$t] = $init; $body: $bt" =>
          //println(s"Running rwr code!\n\tarr = $arr\n\tinit = $init\n\tbody = $body")
          
          val newBody = body rewrite {
            case ir"$$arr.size" => ir"42"
          }
          
          //println(s"New body: $newBody")
          
          val closedBody = newBody subs 'arr -> ((throw new RewriteAbort):IR[arr.Typ,{}])
          
          //println(s"Closed body: $newBody")
          
          closedBody
      }
    }
    
    sameDefsAfter( ir{
      val arr = ArrayBuffer(1,2,3)
      arr append 1
      arr.size
    }, _ transformWith Tr)
    
    sameDefs( ir{
      //val arr = ArrayBuffer(1,2,3) // produces an annoying Vararg node...
      val arr = new ArrayBuffer[Int]
      arr.size + arr.size
    } transformWith Tr, ir{
      (42:Int) + 42
    })
    
    
    // Q: let-bind holes?
    // TODO test
    //  case ir"($a: ArrayBuffer[$vt]); $body: $bt" =>
    
  }
  
  
  
  test("Speculative Rewritings") {
    
    object Tr extends SimpleRuleBasedTransformer with TopDownTransformer with Sqd.SelfTransformer {
      rewrite {
        case block @ ir"val $arr: ArrayBuffer[Int] = $init; $body: $bt" =>
          //println(s"Running rwr code!\n\tarr = $arr\n\tinit = $init\n\tbody = $body")
          
          block eqt body
          
          // To avoid ever-expanding the program!
          val rewritten = mutable.Set[Sqd.Rep]()
          
          // The `rewrite` macro expands to a `FixPointTransformer`
          val newBody = body rewrite {
            case ir"$$arr append $v" if !rewritten(v.rep) =>
              val newV = ir"$v + 1"
              rewritten += newV.rep
              ir"$arr append $newV"
              
            case s @ ir"$$arr.size" if !rewritten(s.rep) =>
              val r = ir"$arr.size"
              rewritten += r.rep
              ir"$r+1"
          }
          
          // Note that in the above rewriting, references to `arr` are converted on-the-fly to holes;
          // but we can we still match them with `$$arr` thanks to hole memory
          
          //println(s"New body: $newBody")
          
          ir"val arr = $init; $newBody"
      }
    }
    
    // FIXME make it work when the xtor does not have the same name!! (cf: proper hole memory)
    
    //base debugFor 
    sameDefs( ir{
      //val arr = ArrayBuffer(1,2,3) // produces an annoying Vararg node...
      val arr = new ArrayBuffer[Int]
      arr append 0
      arr append 1
      arr.size
      42
    } transformWith Tr, ir{
      val buf = new ArrayBuffer[Int]
      buf append ((0:Int)+1)
      buf append ((1:Int)+1)
      buf.size+1
      42
    })
    
  }
  
  
  
  
  
  
  
}

