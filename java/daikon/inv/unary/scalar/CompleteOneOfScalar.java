package daikon.inv.unary.scalar;

import daikon.PptSlice;
import daikon.VarInfo;
import daikon.inv.DiscardInfo;
import daikon.inv.Invariant;
import daikon.inv.InvariantStatus;
import daikon.inv.OutputFormat;
import daikon.inv.ValueSet;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.framework.qual.Unused;
import typequals.prototype.qual.Prototype;

/**
 * Tracks every unique value and how many times it occurs. Prints as {@code x has values: v1 v2 v3
 * ...}.
 */
public final class CompleteOneOfScalar extends SingleScalar {
  static final long serialVersionUID = 20091210L;

  /** Information about each value encountered. */
  public static class Info implements Serializable {
    static final long serialVersionUID = 20091210L;
    public long val;
    public int cnt;

    public Info(long val, int cnt) {
      this.val = val;
      this.cnt = cnt;
    }
  }

  /** List of values seen. */
  @Unused(when = Prototype.class)
  @SuppressWarnings("serial")
  public List<Info> vals;

  /** Boolean. True iff CompleteOneOfScalar invariants should be considered. */
  public static boolean dkconfig_enabled = false;

  public CompleteOneOfScalar(PptSlice slice) {
    super(slice);
    vals = new ArrayList<Info>();
  }

  public @Prototype CompleteOneOfScalar() {
    super();
  }

  private static @Prototype CompleteOneOfScalar proto = new @Prototype CompleteOneOfScalar();

  /** Returns the prototype invariant for CompleteOneOFScalar. */
  public static @Prototype CompleteOneOfScalar get_proto() {
    return proto;
  }

  /** returns whether or not this invariant is enabled */
  @Override
  public boolean enabled() {
    return dkconfig_enabled;
  }

  /** instantiate an invariant on the specified slice */
  @Override
  public CompleteOneOfScalar instantiate_dyn(@Prototype CompleteOneOfScalar this, PptSlice slice) {
    return new CompleteOneOfScalar(slice);
  }

  /** Return description of invariant. Only Daikon format is implemented. */
  @SideEffectFree
  @Override
  public String format_using(@GuardSatisfied CompleteOneOfScalar this, OutputFormat format) {
    if (format == OutputFormat.DAIKON) {
      String out = var().name() + " has values: ";
      for (Info val : vals) {
        out += String.format(" %s[%d]", val.val, val.cnt);
      }
      return out;
    } else {
      return format_unimplemented(format);
    }
  }

  /** Check to see if a only contains printable ascii characters. */
  @Override
  public InvariantStatus add_modified(long a, int count) {
    return check_modified(a, count);
  }

  /** Check to see if a only contains printable ascii characters. */
  @Override
  public InvariantStatus check_modified(long a, int count) {
    for (Info val : vals) {
      if (val.val == a) {
        val.cnt += count;
        return InvariantStatus.NO_CHANGE;
      }
    }
    vals.add(new Info(a, count));
    // System.out.printf("check_modified %s%n", format());
    return InvariantStatus.NO_CHANGE;
  }

  @Override
  protected double computeConfidence() {
    ValueSet vs = ppt.var_infos[0].get_value_set();
    // System.out.printf("%s value set = %s%n", ppt.var_infos[0].name(), vs);
    if (vs.size() > 0) {
      return Invariant.CONFIDENCE_JUSTIFIED;
    } else {
      return Invariant.CONFIDENCE_UNJUSTIFIED;
    }
  }

  /**
   * Returns whether or not this is obvious statically. The only check is for static constants which
   * are obviously printable (or not) from their values.
   */
  @Pure
  @Override
  public @Nullable DiscardInfo isObviousStatically(VarInfo[] vis) {
    return super.isObviousStatically(vis);
  }

  /**
   * Same formula if each value is the same and has the same count. Not implemented for now, just
   * presumed to be false.
   */
  @Pure
  @Override
  public boolean isSameFormula(Invariant o) {
    return false;
  }
}
