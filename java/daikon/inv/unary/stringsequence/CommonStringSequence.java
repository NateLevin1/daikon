package daikon.inv.unary.stringsequence;

import daikon.*;
import daikon.inv.*;
import utilMDE.*;


public class CommonStringSequence extends SingleStringSequence {

  // Variables starting with dkconfig_ should only be set via the
  // daikon.config.Configuration interface.
  public static boolean dkconfig_enabled = true;

  final static boolean debugCommonStringSequence = false;

  private int elts;
  private String[] intersect = null;

  protected CommonStringSequence(PptSlice ppt) {
    super(ppt);
  }

  public static CommonStringSequence instantiate(PptSlice ppt) {
    if (!dkconfig_enabled) return null;
    return new CommonStringSequence(ppt);
  }

  public String repr() {
    return "CommonStringSequence " + varNames() + ": "
      + "elts=\"" + elts;
  }

  private String printIntersect() {
    if (intersect==null)
      return "{}";

    String result = "{";
    for (int i=0; i<intersect.length; i++) {
      result += intersect[i];
      if (i!=intersect.length-1)
	result += ", ";
    }
    result += "}";
    return result;
  }


  public String format() {
    if (debugCommonStringSequence) {
      System.out.println(repr());
    }
    return (printIntersect() + " subset of " + var().name);
  }

  /* IOA */
  public String format_ioa(String classname) {
    String vname = var().name.ioa_name(classname);
    return (printIntersect() + " \\in " + vname);
  }

  public String format_esc() {
    return "format_esc " + this.getClass() + " needs to be changed: " + format();
  }

  public String format_simplify() {
    return "format_simplify " + this.getClass() + " needs to be changed: " + format();
  }

  public void add_modified(String[] a, int count) {
    if (intersect==null)
      intersect = a;
    else {
      String[] tmp = new String[intersect.length];
      int    size = 0;
      for (int i=1; i<a.length; i++)
	if ((ArraysMDE.indexOf(intersect, a[i])!=-1) &&
	    ((size==0) ||
	     (ArraysMDE.indexOf(ArraysMDE.subarray(tmp,0,size), a[i])==-1)))
	  tmp[size++] = a[i];

      if (size==0) {
	destroy();
	return;
      }
      intersect = ArraysMDE.subarray(tmp, 0, size);
    }
    intersect = (String[]) Intern.intern(intersect);
    elts++;
  }

  protected double computeProbability() {
    if (no_invariant) {
      return Invariant.PROBABILITY_NEVER;
    } else {
      return Math.pow(.9, elts);
    }
  }

  public boolean isObviousImplied() {
    return false;
  }

  public boolean isSameFormula(Invariant other) {
    Assert.assert(other instanceof CommonStringSequence);
    return true;
  }
}
