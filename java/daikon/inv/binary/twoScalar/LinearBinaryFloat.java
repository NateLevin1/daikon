// ***** This file is automatically generated from LinearBinary.java.jpp

package daikon.inv.binary.twoScalar;

import daikon.*;
import daikon.inv.Invariant;
import daikon.derive.unary.SequenceLength;
import java.util.*;
import utilMDE.*;

public class LinearBinaryFloat 
  extends TwoFloat 
{
  // We are Serializable, so we specify a version to allow changes to
  // method signatures without breaking serialization.  If you add or
  // remove fields, you should change this number to the current date.
  static final long serialVersionUID = 20020122L;

  // Variables starting with dkconfig_ should only be set via the
  // daikon.config.Configuration interface.
  /**
   * Boolean.  True iff LinearBinary invariants should be considered.
   **/
  public static boolean dkconfig_enabled = true;

  public LinearBinaryCoreFloat  core;

  protected LinearBinaryFloat (PptSlice ppt) {
    super(ppt);
    core = new LinearBinaryCoreFloat (this);
  }

  public static LinearBinaryFloat  instantiate(PptSlice ppt) {
    if (!dkconfig_enabled) return null;
    if (ppt.debugged) {
      ppt.debug.debug("LinearBinaryFloat"  + ".instantiate(" + ppt.name + ")");
    }
    return new LinearBinaryFloat (ppt);
  }

  protected Object clone() {
    LinearBinaryFloat  result = (LinearBinaryFloat) super.clone();
    result.core = (LinearBinaryCoreFloat) core.clone();
    result.core.wrapper = result;
    return result;
  }

  protected Invariant resurrect_done_swapped() {
    core.swap();
    return this;
  }

  public String repr() {
    return "LinearBinaryFloat"  + varNames() + ": "
      + "falsified=" + falsified
      + "; " + core.repr();
  }

  public String format_using(OutputFormat format) {
    return core.format_using(format, var1().name, var2().name);
  }

  // XXX core needs to change to do flow
  public void add_modified(double  x, double  y, int count) {
    core.add_modified(x, y, count);
  }

  public boolean enoughSamples() {
    return core.enoughSamples();
  }

  protected double computeProbability() {
    return core.computeProbability();
  }

  public boolean isExact() {
    return true;
  }

  public boolean isObviousDerived() {
    VarInfo var1 = ppt.var_infos[0];
    VarInfo var2 = ppt.var_infos[1];
    // avoid comparing "size(a)" to "size(a)-1"; yields "size(a)-1 = size(a) - 1"
    if (var1.isDerived() && (var1.derived instanceof SequenceLength)
        && var2.isDerived() && (var2.derived instanceof SequenceLength)) {
      SequenceLength sl1 = (SequenceLength) var1.derived;
      SequenceLength sl2 = (SequenceLength) var2.derived;
      if (sl1.base == sl2.base) {
        return true;
      }
    }
    // avoid comparing "size(a)-1" to anything; should compare "size(a)" instead
    if (var1.isDerived() && (var1.derived instanceof SequenceLength)
        && ((SequenceLength) var1.derived).shift != 0) {
      return true;
    }
    if (var2.isDerived() && (var2.derived instanceof SequenceLength)
        && ((SequenceLength) var2.derived).shift != 0) {
      return true;
    }

    return false;
  }

  public boolean isSameFormula(Invariant other)
  {
    return core.isSameFormula(((LinearBinaryFloat) other).core);
  }

  public boolean isExclusiveFormula(Invariant other)
  {
    if (other instanceof LinearBinaryFloat ) {
      return core.isExclusiveFormula(((LinearBinaryFloat) other).core);
    }
    return false;
  }

  // Look up a previously instantiated invariant.
  public static LinearBinaryFloat  find(PptSlice ppt) {
    Assert.assertTrue(ppt.arity == 2);
    for (Iterator itor = ppt.invs.iterator(); itor.hasNext(); ) {
      Invariant inv = (Invariant) itor.next();
      if (inv instanceof LinearBinaryFloat )
        return (LinearBinaryFloat) inv;
    }
    return null;
  }

  // Returns a vector of LinearBinary objects.
  // This ought to produce an iterator instead.
  public static Vector findAll(VarInfo vi) {
    Vector result = new Vector();
    for (Iterator itor = vi.ppt.views_iterator() ; itor.hasNext() ; ) {
      PptSlice view = (PptSlice) itor.next();
      if ((view.arity == 2) && view.usesVar(vi)) {
        LinearBinaryFloat  lb = LinearBinaryFloat.find(view);
        if (lb != null) {
          result.add(lb);
        }
      }
    }
    return result;
  }

}
