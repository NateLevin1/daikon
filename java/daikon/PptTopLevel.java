package daikon;

import daikon.derive.*;
import daikon.derive.unary.*;
import daikon.derive.binary.*;
import daikon.derive.ternary.*;
import daikon.inv.*;
import daikon.inv.unary.*;
import daikon.inv.unary.scalar.*;
import daikon.inv.unary.sequence.*;
import daikon.inv.unary.stringsequence.*;
import daikon.inv.Invariant.OutputFormat;
import daikon.inv.binary.*;
import daikon.inv.ternary.*;
import daikon.inv.binary.twoScalar.*;
import daikon.inv.binary.twoString.*;
import daikon.inv.binary.twoSequence.*;
import daikon.simplify.*;
import daikon.split.*;
import daikon.split.misc.*;
import daikon.suppress.*;
import utilMDE.Assert;
import daikon.inv.filter.InvariantFilters;

import java.util.*;
import java.text.*;

import java.util.logging.Logger;
import java.util.logging.Level;

import utilMDE.*;

/**
 * All information about a single program point.
 * A Ppt may also represent just part of the data: see PptConditional.
 * <p>
 * PptTopLevel doesn't do any direct computation, instead deferring that
 * to its views that are slices and that actually contain the invariants.
 * <p>
 * The data layout is as follows:
 * <ul>
 * <li>A PptMap is a collection of PptTopLevel objects.
 * <li>A PptTopLevel contains PptSlice objects, one for each set of
 * variables at the program point.  For instance, if a PptTopLevel has
 * variables a, b, and c, then it has three PptSlice1 objects (one for a;
 * one for b; and one for c), three PptSlice2 objects (one for a,b; one for
 * a,c; and one for b,c), and one PptSlice3 object (for a,b,c).
 * <li>A PptSlice object contains invariants.  When a sample (a tuple of
 * variable values) is fed to a PptTopLevel, it in turn feeds it to all the
 * slices, which feed it to all the invariants, which act on it
 * appropriately.
 * </ul>
 **/
public class PptTopLevel extends Ppt {
  // We are Serializable, so we specify a version to allow changes to
  // method signatures without breaking serialization.  If you add or
  // remove fields, you should change this number to the current date.
  static final long serialVersionUID = 20040803L;

  // Variables starting with dkconfig_ should only be set via the
  // daikon.config.Configuration interface.
  /**
   * Boolean.  If true, create implications for all pairwise
   * combinations of conditions, and all pairwise combinations of exit
   * points.  If false, create implications for only the first
   * two conditions, and create implications only if there are
   * exactly two exit points.
   **/
  public static boolean dkconfig_pairwise_implications = false;

  /**
   * Remove invariants at lower program points when a matching invariant is
   * created at a higher program point. For experimental purposes only.
   */
  public static boolean dkconfig_remove_merged_invs = false;

  /**
   * Boolean.  If true, flow global invariants that are falsified
   * immediately to all children as opposed to waiting for the next sample
   * to arrive at that child.
   */
  public static boolean dkconfig_flow_globals_immed = false;

  /** Number of invariants after equality set processing for the last sample. */
  public int instantiated_inv_cnt = 0;

  /** Number of slices after equality set processing for the last sample. */
  public int instantiated_slice_cnt = 0;

  /** Main debug tracer. **/
  public static final Logger debug = Logger.getLogger("daikon.PptTopLevel");

  /** Debug tracer for instantiated slices. **/
  public static final Logger debugInstantiate =
    Logger.getLogger("daikon.PptTopLevel.instantiate");

  /** Debug tracer for timing merges. **/
  public static final Logger debugTimeMerge =
    Logger.getLogger("daikon.PptTopLevel.time_merge");

  /** Debug tracer for equalTo checks. **/
  public static final Logger debugEqualTo =
    Logger.getLogger("daikon.PptTopLevel.equal");

  /** Debug tracer for addImplications. **/
  public static final Logger debugAddImplications =
    Logger.getLogger("daikon.PptTopLevel.addImplications");

  /** Debug tracer for adding and processing conditional ppts. */
  public static final Logger debugConditional =
    Logger.getLogger("daikon.PptTopLevel.conditional");

  /** Debug tracer for data flow. **/
  public static final Logger debugFlow = Logger.getLogger("daikon.flow.flow");

  /** Debug tracer for up-merging equality sets.  **/
  public static final Logger debugMerge =
    Logger.getLogger("daikon.PptTopLevel.merge");

  /** Debug tracer for global ppt. **/
  public static final Logger debugGlobal =
    Logger.getLogger("daikon.PptTopLevel.global");

  /** Debug tracer for NIS suppression statistics **/
  public static final Logger debugNISStats =
    Logger.getLogger("daikon.PptTopLevel.NISStats");

  // These used to appear in Ppt, were moved down to PptToplevel
  public final String name;
  public final PptName ppt_name;

  public final String name() {
    return name;
  }

  /** Permutation to swap the order of variables in a binary invariant **/
  private static int[] permute_swap = new int[] { 1, 0 };

  /** Holds the falsified invariants under this PptTopLevel. */
  public ArrayList falsified_invars = new ArrayList();

  /** List of constant variables. */
  public DynamicConstants constants = null;

  // Do we need both a num_tracevars for the number of variables in the
  // tracefile and a num_non_derived_vars for the number of variables
  // actually passed off to this Ppt?  The ppt wouldn't use num_tracevars,
  // but it would make sense to store it here anyway.

  // These values are -1 if not yet set (can that happen?). // No they're not
  // Invariant:  num_declvars == num_tracevars + num_orig_vars
  int num_declvars; // number of variables in the decl file
  int num_tracevars; // number of variables in the trace file
  int num_orig_vars; // number of _orig vars
  int num_static_constant_vars; // these don't appear in the trace file

  private int values_num_samples;

  ModBitTracker mbtracker;

  ValueSet[] value_sets;

  /**
   * All the Views (that is, slices) on this are stored as values in
   * the HashMap
   * Provided so that this Ppt can notify them when significant events
   * occur, such as receiving a new value, deriving variables, or
   * discarding data.
   * Indexed by a Arrays.asList array list of Integers holding
   * varinfo_index values
   **/
  private Map /*[Integer[]->PptSlice]*/
  views;

  /** List of all of the splitters for this ppt. */
  public /*PptSplitter*/
  ArrayList splitters = null;

  /**
   * Iterator for all of the conditional ppts.  Returns each PptConditional
   * from each entry in splitters
   */
  public class CondIterator implements java.util.Iterator {

    int splitter_index = 0;
    int ppts_index = 0;

    public boolean hasNext() {
      if (splitters == null)
        return (false);
      if (splitter_index >= splitters.size())
        return (false);
      return (true);
    }

    public Object next() {

      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      PptSplitter ppt_split = (PptSplitter) splitters.get(splitter_index);
      PptTopLevel ppt = ppt_split.ppts[ppts_index];

      if (ppts_index < (ppt_split.ppts.length - 1)) {
        ppts_index++;
      } else {
        splitter_index++;
        ppts_index = 0;
      }
      return (ppt);
    }

    public void remove() {
      throw new UnsupportedOperationException("Remove unsupported in CondIterator");
    }
  }

  public CondIterator cond_iterator() {
    return new CondIterator();
  }

  /** Returns whether or not this ppt has any splitters. */
  public boolean has_splitters() {
    return (splitters != null) && (splitters.size() > 0);
  }

  /** All children relations in the hierarchy. */
  public List /* PptRelation */
  children = new ArrayList();

  /** All parent relations in the hierarchy. */
  public List /* PptRelation */
  parents = new ArrayList();

  /**
   *  Flag that indicates whether or not invariants have been merged
   *  from all of this ppts children to form the invariants here.  Necessary
   *  because a ppt can have multiple parents and otherwise we'd needlessly
   *  merge multiple times
   */
  public boolean invariants_merged = false;

  /**
   * Flag that indicates whether or not invariants that are duplicated
   * at the parent have been removed..
   */
  public boolean invariants_removed = false;

  /**
   * VarInfo index transform from this point to the ppt containing
   * all of the global variables.  The index into global transform is
   * the value_index in global ppt.  The result (global_transform[index])
   * is the value_index of the corresponding variable in this ppt
   *
   * Ther are two transforms for each exit point. Orig variables are
   * handled by global_transform_orig and post variables are handled
   * by global_transform_post.  This is necessary because bottom up
   * only processes numbered exit points, so both the orig and the post
   * value must be separately applied to the global ppt.
   */
  public int[] global_transform_orig = null;
  /** @see #global_transform_orig */
  public int[] global_transform_post = null;

  /** Global ppt (if any.) **/
  public static PptTopLevel global = null;

  /** List of weakened invariants at the global ppt. */
  public static List global_weakened_invs = new ArrayList();

  public static int global_weakened_start_index = 0;

  /**
   * Set of all PptTopLevels where the ordering provided by the
   * links is sorted from the smallest offset to the largest offset
   * This takes advantage of LinkedHashSet predictable ordering over
   * elements (insertion-order).
   */
  public static Set /* PptTopLevel */
  global_weakened_offsets = new LinkedHashSet();

  /** offset of this ppt into the list of weakened invariants **/
  private int global_weakened_offset = 0;

  /**
   * Together, dataflow_ppts and dataflow_tranforms describe how
   * samples that are received at this program point flow to other
   * points.  If samples are not received at this point, both are
   * null.  If samples are received at this point, then both have
   * the same length and:
   *
   * <li>dataflow_ppts includes this (as its last element);
   *
   * <li>dataflow_ppts is ordered by the way samples will flow.  It is
   * a topological sort of the ancestors of this ppt, not just immediate
   * parents.
   *
   * <li>dataflow_transforms contains functions from this to
   * dataflow_ppts; each function is an int[] whose domain is
   * indices of var_infos in this, and whose range is indices of
   * var_infos in the corresponding element of dataflow_ppts;
   *
   * <li>dataflow_transforms describes the function from the var_infos
   * of this ppt to the same variable in dataflow_ppts, so its inner
   * length equals this.var_infos.length;
   *
   * <li>program points in dataflow_ppts may be repeated if a sample
   * at this point induces more than one sample at another point.
   * (For example, if a method has two arguments of type Foo, then a
   * sample for the method induces two different samples at
   * Foo:::OBJECT.)
   **/
  public PptTopLevel[] dataflow_ppts;
  /** @see #dataflow_ppts */
  public int[][] dataflow_transforms;

  /**
   * Together, invflow_ppts and invflow_tranforms describe how
   * invariants that are changed or falsified at this program point
   * flow to other points.  They are never null, but may be
   * zero-length if there are no lower ppts.  They obey the following
   * invariants:
   *
   * <li>invflow_transforms contains functions from this to
   * invflow_ppts; each function is an int[] whose domain is
   * indices of var_infos in this, and whose range is indices of
   * var_infos in the corresponding element of invflow_ppts;
   *
   * <li>invflow_transforms describes the function from the var_infos
   * of this ppt to the same variable in invflow_ppts, so its inner
   * length equals this.var_infos.length;
   *
   * <li>program points in invflow_ppts may be repeated if a sample
   * at this point induces more than one sample another point.
   * (For example, if a method has two arguments of type Foo, then a
   * sample for the method induces two different samples at
   * Foo:::OBJECT.)
   **/
  public PptTopLevel[] invflow_ppts;
  /** @see #invflow_ppts */
  public int[][] invflow_transforms;

  // This was renamed to the joiner_view because it no longer just for
  // implications, but instead for any Invariants that represents a
  // "joining" of two others (such as and, or, etc)
  public PptSlice0 joiner_view = new PptSlice0(this);

  /**
   * Holds Equality invariants.  Never null after invariants are
   * instantiated.
   **/
  public PptSliceEquality equality_view;

  // The set of redundant_invs is filled in by the below method
  // mark_implied_via_simplify.  Contents are either Invariant
  // objects, or, in the case of Equality invariants, the canonical
  // VarInfo for the equality.
  public Set redundant_invs = new LinkedHashSet(0);

  public PptTopLevel(String name, VarInfo[] var_infos) {
    this.name = name;
    ppt_name = new PptName(name);
    this.var_infos = var_infos;
    int val_idx = 0;
    num_static_constant_vars = 0;
    for (int i = 0; i < var_infos.length; i++) {
      VarInfo vi = var_infos[i];
      vi.varinfo_index = i;
      if (vi.is_static_constant) {
        vi.value_index = -1;
        num_static_constant_vars++;
      } else {
        vi.value_index = val_idx;
        val_idx++;
      }
      vi.ppt = this;
    }
    for (int i = 0; i < var_infos.length; i++) {
      VarInfo vi = var_infos[i];
      Assert.assertTrue((vi.value_index == -1) || (!vi.is_static_constant));
    }

    views = new LinkedHashMap();

    num_declvars = var_infos.length;
    num_tracevars = val_idx;
    num_orig_vars = 0;
    Assert.assertTrue(num_static_constant_vars == num_declvars - num_tracevars);
    // System.out.println("Created PptTopLevel " + name() + ": "
    //                    + "num_static_constant_vars=" + num_static_constant_vars
    //                    + ",num_declvars=" + num_declvars
    //                    + ",num_tracevars=" + num_tracevars);

    Assert.assertTrue(
      num_tracevars == var_infos.length - num_static_constant_vars);
    mbtracker = new ModBitTracker(num_tracevars);
    value_sets = new ValueSet[num_tracevars];
    for (int i = 0; i < var_infos.length; i++) {
      VarInfo vi = var_infos[i];
      int value_index = vi.value_index;
      if (value_index == -1) {
        continue;
      }
      Assert.assertTrue(value_sets[value_index] == null);
      value_sets[value_index] = ValueSet.factory(vi);
    }
    for (int i = 0; i < num_tracevars; i++) {
      Assert.assertTrue(value_sets[i] != null);
    }
  }

  public static void init(PptMap all_ppts) {

    // setup all of the static structures.  This needs to be done
    // here, because in testing we sometimes run multiple tests (each
    // of which needs to appear to have started from scratch)
    global_weakened_invs = new ArrayList();
    global_weakened_start_index = 0;
    global_weakened_offsets = new LinkedHashSet();

    // Init the set of ppts used to track the index into the weakened invs
    // list.  The initial order is irrelevant since each needs to start
    // at the beginning of the weakened_invs list.
    for (Iterator i = all_ppts.ppt_all_iterator(); i.hasNext();) {
      PptTopLevel ppt = (PptTopLevel) i.next();
      if (ppt.ppt_name.isExitPoint() && !ppt.ppt_name.isCombinedExitPoint())
        global_weakened_offsets.add(ppt);
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Accessing data
  ///

  public int num_vars() {
    return var_infos.length;
  }

  // Returns true iff this is the only exit point or the combined exit point
  public boolean isMainExit() {
    return ppt_name.isExitPoint();
  }

  // Appears to be used only in the memory monitor.
  public int num_array_vars() {
    int num_arrays = 0;
    for (int i = 0; i < var_infos.length; i++)
      if (var_infos[i].rep_type.isArray())
        num_arrays++;
    return num_arrays;
  }

  public Iterator var_info_iterator() {
    return Arrays.asList(var_infos).iterator();
  }

  // This method is added as somewhat of a hack for the TreeGUI.  In the
  // gui, PptTopLevel are stored as nodes in a tree.  Swing obtains the
  // string to display in the actual JTree by calling toString().
  public String toString() {
    if (ppt_name.isObjectInstanceSynthetic()) // display "MyClassName : OBJECT"
      return ppt_name.getFullClassName() + " : " + FileIO.object_suffix;
    else if (
      ppt_name.isClassStaticSynthetic()) // display "MyClassName : CLASS"
      return ppt_name.getFullClassName() + " : " + FileIO.class_static_suffix;
    else // only display "EXIT184"
      return ppt_name.getPoint();
  }

  /** Trim the collections used in this PptTopLevel, in hopes of saving space. **/
  public void trimToSize() {
    super.trimToSize();
    if (splitters != null) {
      splitters.trimToSize();
    }
  }

  /** The number of samples processed by this program point so far. **/
  public int num_samples() {
    return values_num_samples;
  }

  public int num_samples(VarInfo vi1) {
    if (vi1.is_static_constant) {
      return mbtracker.num_samples();
    }
    BitSet b1 = mbtracker.get(vi1.value_index);
    int num_slice_samples = b1.cardinality();
    return num_slice_samples;
  }

  public int num_samples(VarInfo vi1, VarInfo vi2) {
    if (vi1.is_static_constant) {
      return num_samples(vi2);
    }
    if (vi2.is_static_constant) {
      return num_samples(vi1);
    }
    BitSet b1 = mbtracker.get(vi1.value_index);
    BitSet b2 = mbtracker.get(vi2.value_index);
    int num_slice_samples = UtilMDE.intersectionCardinality(b1, b2);
    return num_slice_samples;
  }

  public int num_samples(VarInfo vi1, VarInfo vi2, VarInfo vi3) {
    if (vi1.is_static_constant) {
      return num_samples(vi2, vi3);
    }
    if (vi2.is_static_constant) {
      return num_samples(vi1, vi3);
    }
    if (vi3.is_static_constant) {
      return num_samples(vi1, vi2);
    }
    BitSet b1 = mbtracker.get(vi1.value_index);
    BitSet b2 = mbtracker.get(vi2.value_index);
    BitSet b3 = mbtracker.get(vi3.value_index);
    int num_slice_samples = UtilMDE.intersectionCardinality(b1, b2, b3);
    return num_slice_samples;
  }

  /** The number of distinct values that have been seen. **/
  public int num_values(VarInfo vi1) {
    if (vi1.is_static_constant) {
      // This test is deeply wrong; I should always return 1.  But see what
      // effect this has.
      if (Daikon.dkconfig_df_bottom_up) {
        return 1;
      } else {
        return 0;
      }
    }
    ValueSet vs1 = value_sets[vi1.value_index];
    return vs1.size();
  }

  /** An upper bound on the number of distinct values that have been seen. **/
  public int num_values(VarInfo vi1, VarInfo vi2) {
    return num_values(vi1) * num_values(vi2);
  }

  /** An upper bound on the number of distinct values that have been seen. **/
  public int num_values(VarInfo vi1, VarInfo vi2, VarInfo vi3) {
    return num_values(vi1) * num_values(vi2) * num_values(vi3);
  }

  // Get the actual views from the HashMap
  Collection viewsAsCollection() {
    return views.values();
  }

  // Quick access to the number of views, since the views variable is private
  public int numViews() {
    return views.size();
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Adding variables
  ///

  // Some of this should perhaps be moved up into Ppt.

  /**
   * Appends vi to the var_infos array of this ppt.  Also sets vi's
   * varinfo_index, value_index, and ppt fields.  Method is
   * non-private so that FileIO can access it; it should not be called
   * by other classes.
   * @param vi must not be a static constant VarInfo
   **/
  void addVarInfo(VarInfo vi) {
    VarInfo[] vis = new VarInfo[] { vi };
    addVarInfos(vis);
  }

  /**
   * Has the effect of performing addVarInfo(VarInfo) over all
   * elements in vis.  Method is not private so that FileIO can access
   * it; should not be called by other classes.
   * @param vis must not contain static constant VarInfos
   * @see #addVarInfo(VarInfo)
   **/
  void addVarInfos(VarInfo[] vis) {
    if (vis.length == 0)
      return;
    int old_length = var_infos.length;
    VarInfo[] new_var_infos = new VarInfo[var_infos.length + vis.length];
    Assert.assertTrue(mbtracker.num_samples() == 0);
    mbtracker = new ModBitTracker(mbtracker.num_vars() + vis.length);
    System.arraycopy(var_infos, 0, new_var_infos, 0, old_length);
    System.arraycopy(vis, 0, new_var_infos, old_length, vis.length);
    for (int i = old_length; i < new_var_infos.length; i++) {
      VarInfo vi = new_var_infos[i];
      vi.varinfo_index = i;
      vi.value_index = i - num_static_constant_vars;
      vi.ppt = this;
    }
    var_infos = new_var_infos;
    int old_vs_length = value_sets.length;
    ValueSet[] new_value_sets = new ValueSet[old_vs_length + vis.length];
    System.arraycopy(value_sets, 0, new_value_sets, 0, old_vs_length);
    for (int i = 0; i < vis.length; i++) {
      new_value_sets[old_vs_length + i] = ValueSet.factory(vis[i]);
    }
    value_sets = new_value_sets;
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Derived variables
  ///

  // Convenience function for PptConditional initializer (which can't
  // contain statements but can call a function).
  public VarInfo[] trace_and_orig_and_const_vars() {
    // Not ArraysMDE.subarray(var_infos, 0, num_tracevars + num_orig_vars)
    // because its result Object[] cannot be cast to VarInfo[].
    int total_vars = num_tracevars + num_orig_vars + num_static_constant_vars;
    VarInfo[] result = new VarInfo[total_vars];
    System.arraycopy(var_infos, 0, result, 0, total_vars);
    return result;
  }

  // This is here because I think it doesn't make sense to derive except
  // from a PptTopLevel (and possibly a PptConditional?).  Perhaps move it
  // to another class later.

  public static boolean worthDerivingFrom(VarInfo vi) {

    // This prevents derivation from ever occurring on
    // derived variables.  Ought to put this under the
    // control of the individual Derivation objects.

    // System.out.println("worthDerivingFrom(" + vi.name + "): "
    //                    + "derivedDepth=" + vi.derivedDepth()
    //                    + ", isCanonical=" + vi.isCanonical()
    //                    + ", canBeMissing=" + vi.canBeMissing);
    return ((vi.derivedDepth() < 2));

    // Should add this (back) in:
    // && !vi.always_missing()
    // && !vi.always_equal_to_null();

    // Testing for being canonical is going to be a touch tricky when we
    // integrate derivation and inference, because when something becomes
    // non-canonical we'll have to go back and derive from it, etc.  It's
    // almost as if that is a new variable appearing.  But it did appear in
    // the list until it was found to be equal to another and removed from
    // the list!  I need to decide whether the time savings of not
    // processing the non-canonical variables are worth the time and
    // complexity of making variables non-canonical and possibly canonical
    // again.

  }

  // To verify that these are all the factories of interest, do
  // cd ~/research/invariants/daikon/derive; search -i -n 'extends.*derivationfactory'

  transient UnaryDerivationFactory[] unaryDerivations =
    new UnaryDerivationFactory[] {
      new SequenceLengthFactory(),
      new SequenceInitialFactory(),
      new SequenceMinMaxSumFactory(),
      new SequenceInitialFactoryFloat(),
      };

  transient BinaryDerivationFactory[] binaryDerivations =
    new BinaryDerivationFactory[] {
    // subscript
    new SequenceScalarSubscriptFactory(),
      new SequenceFloatSubscriptFactory(),
      new SequenceStringSubscriptFactory(),
    // intersection
    new SequenceScalarIntersectionFactory(),
      new SequenceFloatIntersectionFactory(),
      new SequenceStringIntersectionFactory(),
    // union
    new SequenceScalarUnionFactory(),
      new SequenceFloatUnionFactory(),
      new SequenceStringUnionFactory(),
    // other
    new SequencesConcatFactory(),
      new SequencesJoinFactory(),
      new SequencesPredicateFactory(),
      };

  transient TernaryDerivationFactory[] ternaryDerivations =
    new TernaryDerivationFactory[] {
      new SequenceScalarArbitrarySubsequenceFactory(),
      new SequenceStringArbitrarySubsequenceFactory(),
      new SequenceFloatArbitrarySubsequenceFactory(),
      };

  /**
   * This routine creates derivations for one "pass"; that is, it adds
   * some set of derived variables, according to the functions that
   * are passed in.  All the results involve at least one VarInfo
   * object at an index i such that vi_index_min <= i < vi_index_limit
   * (and possibly other VarInfos outside that range).
   * @return a Vector of VarInfo
   **/
  private Derivation[] derive(int vi_index_min, int vi_index_limit) {
    boolean debug_bin_possible = false;

    UnaryDerivationFactory[] unary = unaryDerivations;
    BinaryDerivationFactory[] binary = binaryDerivations;
    TernaryDerivationFactory[] ternary = ternaryDerivations;

    // optimize track logging, otherwise it really takes a lot of time
    if (Debug.logOn()) {
      for (int di = 0; di < binary.length; di++) {
        BinaryDerivationFactory d = binary[di];
        if (Debug.class_match(d.getClass()))
          debug_bin_possible = true;
      }
    }

    if (Global.debugDerive.isLoggable(Level.FINE)) {
      Global.debugDerive.fine("Deriving one pass for ppt " + this.name);
      Global.debugDerive.fine(
        "vi_index_min="
          + vi_index_min
          + ", vi_index_limit="
          + vi_index_limit
          + ", unary.length="
          + unary.length
          + ", binary.length="
          + binary.length
          + ", ternary.length="
          + ternary.length);
    }

    Collection result = new ArrayList();

    for (int i = vi_index_min; i < vi_index_limit; i++) {
      VarInfo vi = var_infos[i];
      if (Global.debugDerive.isLoggable(Level.FINE)) {
        Global.debugDerive.fine(
          "Unary: trying to derive from " + vi.name.name());
      }
      if (!worthDerivingFrom(vi)) {
        if (Global.debugDerive.isLoggable(Level.FINE)) {
          Global.debugDerive.fine(
            "Unary: not worth deriving from " + vi.name.name());
        }
        continue;
      }
      for (int di = 0; di < unary.length; di++) {
        UnaryDerivationFactory udf = unary[di];
        UnaryDerivation[] uderivs = udf.instantiate(vi);
        if (uderivs != null) {
          for (int udi = 0; udi < uderivs.length; udi++) {
            UnaryDerivation uderiv = uderivs[udi];
            if ((Daikon.var_omit_regexp != null)
              && Global.regexp_matcher.contains(
                uderiv.getVarInfo().name.name(),
                Daikon.var_omit_regexp)) {
              continue;
            }
            result.add(uderiv);
          }
        }
      }
    }

    // I want to get all pairs of variables such that at least one of the
    // variables is under consideration, but I want to generate each such
    // pair only once.  This probably isn't the most efficient technique,
    // but it's probably adequate and is not excessively complicated or
    // excessively slow.
    for (int i1 = 0; i1 < var_infos.length; i1++) {
      VarInfo vi1 = var_infos[i1];
      if (!worthDerivingFrom(vi1)) {
        if (Global.debugDerive.isLoggable(Level.FINE)) {
          Global.debugDerive.fine(
            "Binary first VarInfo: not worth deriving from " + vi1.name.name());
        }
        continue;
      }
      // This guarantees that at least one of the variables is under
      // consideration.
      // target1 indicates whether the first variable is under consideration.
      boolean target1 = (i1 >= vi_index_min) && (i1 < vi_index_limit);
      int i2_min, i2_limit;
      if (target1) {
        i2_min = i1 + 1;
        i2_limit = var_infos.length;
      } else {
        i2_min = Math.max(i1 + 1, vi_index_min);
        i2_limit = vi_index_limit;
      }
      // if (Global.debugDerive.isLoggable(Level.FINE))
      //   Global.debugDerive.fine ("i1=" + i1
      //                      + ", i2_min=" + i2_min
      //                      + ", i2_limit=" + i2_limit);
      for (int i2 = i2_min; i2 < i2_limit; i2++) {
        VarInfo vi2 = var_infos[i2];
        if (!worthDerivingFrom(vi2)) {
          if (Global.debugDerive.isLoggable(Level.FINE)) {
            Global.debugDerive.fine(
              "Binary: not worth deriving from ("
                + vi1.name.name()
                + ","
                + vi2.name.name()
                + ")");
          }
          continue;
        }
        for (int di = 0; di < binary.length; di++) {
          BinaryDerivationFactory d = binary[di];
          if (debug_bin_possible && Debug.logOn())
            Debug.log(
              d.getClass(),
              vi1.ppt,
              Debug.vis(vi1, vi2),
              "Trying Binary Derivation ");
          BinaryDerivation[] bderivs = d.instantiate(vi1, vi2);
          if (bderivs != null) {
            for (int bdi = 0; bdi < bderivs.length; bdi++) {
              BinaryDerivation bderiv = bderivs[bdi];
              if ((Daikon.var_omit_regexp != null)
                && Global.regexp_matcher.contains(
                  bderiv.getVarInfo().name.name(),
                  Daikon.var_omit_regexp)) {
                continue;
              }
              result.add(bderiv);
              if (Debug.logOn())
                Debug.log(
                  d.getClass(),
                  vi1.ppt,
                  Debug.vis(vi1, vi2),
                  "Created Binary Derivation "
                    + bderiv.getVarInfo().name.name());
            }
          }
        }
      }
    }

    // Ternary derivations follow the same pattern, one level deeper.
    for (int i1 = 0; i1 < var_infos.length; i1++) {
      VarInfo vi1 = var_infos[i1];
      if (vi1.isDerived()) {
        if (Global.debugDerive.isLoggable(Level.FINE)) {
          Global.debugDerive.fine(
            "Ternary first VarInfo: not worth "
              + "deriving from "
              + vi1.name.name());
        }
        continue;
      }
      // This guarantees that at least one of the variables is under
      // consideration.
      // target1 indicates whether the first variable is under consideration.
      boolean target1 = (i1 >= vi_index_min) && (i1 < vi_index_limit);
      int i2_min, i2_limit;
      if (target1) {
        i2_min = i1 + 1;
        i2_limit = var_infos.length;
      } else {
        i2_min = Math.max(i1 + 1, vi_index_min);
        i2_limit = vi_index_limit;
      }
      // if (Global.debugDerive.isLoggable(Level.FINE))
      //   Global.debugDerive.fine ("i1=" + i1
      //                      + ", i2_min=" + i2_min
      //                      + ", i2_limit=" + i2_limit);
      for (int i2 = i2_min; i2 < i2_limit; i2++) {
        VarInfo vi2 = var_infos[i2];
        if (vi2.isDerived()
          || !TernaryDerivationFactory.checkType(vi1, vi2)
          || !TernaryDerivationFactory.checkComparability(vi1, vi2)) {
          if (Global.debugDerive.isLoggable(Level.FINE)) {
            Global.debugDerive.fine(
              "Ternary 2nd: not worth deriving from ("
                + vi1.name.name()
                + ","
                + vi2.name.name()
                + ")");
          }
          continue;
        }
        boolean target2 = (i2 >= vi_index_min) && (i2 < vi_index_limit);
        int i3_min, i3_limit;
        if (target1 || target2) {
          i3_min = i2 + 1;
          i3_limit = var_infos.length;
        } else {
          i3_min = Math.max(i2 + 1, vi_index_min);
          i3_limit = vi_index_limit;
        }
        for (int i3 = i3_min; i3 < i3_limit; i3++) {
          VarInfo vi3 = var_infos[i3];
          if (vi3.isDerived()) {
            if (Global.debugDerive.isLoggable(Level.FINE)) {
              Global.debugDerive.fine(
                "Ternary 3rd: not worth deriving from ("
                  + vi1.name.name()
                  + ","
                  + vi2.name.name()
                  + ")"
                  + vi3.name.name()
                  + ")");
            }
            continue;
          }
          for (int di = 0; di < ternary.length; di++) {
            TernaryDerivationFactory d = ternary[di];
            TernaryDerivation[] tderivs = d.instantiate(vi1, vi2, vi3);
            if (tderivs != null) {
              for (int tdi = 0; tdi < tderivs.length; tdi++) {
                TernaryDerivation tderiv = tderivs[tdi];
                if ((Daikon.var_omit_regexp != null)
                  && Global.regexp_matcher.contains(
                    tderiv.getVarInfo().name.name(),
                    Daikon.var_omit_regexp)) {
                  continue;
                }
                result.add(tderiv);
              }
            } else {
              if (Global.debugDerive.isLoggable(Level.FINE)) {
                Global.debugDerive.fine(
                  "Ternary instantiated but not used: "
                    + vi1.name.name()
                    + " "
                    + vi2.name.name()
                    + " "
                    + vi3.name.name()
                    + " ");
              }
            }
          }
        }
      }
    }

    if (Global.debugDerive.isLoggable(Level.FINE)) {
      Global.debugDerive.fine(
        "Number of derived variables at program point "
          + this.name
          + ": "
          + result.size());
      String derived_vars = "Derived:";
      for (Iterator itor = result.iterator(); itor.hasNext();) {
        derived_vars += " "
          + ((Derivation) itor.next()).getVarInfo().name.name();
      }
      Global.debugDerive.fine(derived_vars);
    }
    Derivation[] result_array =
      (Derivation[]) result.toArray(new Derivation[result.size()]);
    return result_array;
  }

  ///
  /// Adding derived variables
  ///

  // This doesn't compute what the derived variables should be, just adds
  // them after being computed.

  // derivs is a Vector of Derivation objects
  void __addDerivedVariables(Vector derivs) {
    Derivation[] derivs_array =
      (Derivation[]) derivs.toArray(new Derivation[0]);
    __addDerivedVariables(derivs_array);
  }

  void __addDerivedVariables(Derivation[] derivs) {

    VarInfo[] vis = new VarInfo[derivs.length];
    for (int i = 0; i < derivs.length; i++) {
      vis[i] = derivs[i].getVarInfo();
    }
    addVarInfos(vis);

  }

  ///////////////////////////////////////////////////////////////////////////
  /// Manipulating values
  ///

  /**
   * Given a sample that was observed at this ppt, flow it up to
   * any higher ppts and lastly to this ppt.  Hit conditional
   * ppts along the way (via the add method).
   * @param vt the set of values for this and higher ppts to see
   * @param count the number of samples that vt represents
   *
   * Contract: since we hit higher ppts first and check this last,
   * invariants that have flown down from the higher ppt are also
   * checked by this vt.  If we hit this before parents, then the
   * flow wouldn't work.
   **/
  public void add_and_flow(ValueTuple vt, int count) {
    //     if (debugFlow.isLoggable(Level.FINE)) {
    //       debugFlow.fine ("add_and_flow for " + name());
    //     }

    // Doable, but commented out for efficiency
    // repCheck();

    // System.out.println("PptTopLevel " + name() + ": add " + vt);
    Assert.assertTrue(vt.size() == var_infos.length - num_static_constant_vars);

    // The way adding samples works: We have precomputed program
    // points that have any VarInfos that are higher than this point's
    // VarInfos, and a transformation vector that maps the variable
    // index at this point to the variable index in the higher point.
    // Simply walk down that list, transforming value tuples according
    // to transormation vectors.  Then call add of the right program points.

    Assert.assertTrue(dataflow_ppts != null, name);
    Assert.assertTrue(dataflow_transforms != null, name);
    Assert.assertTrue(dataflow_ppts.length == dataflow_transforms.length, name);

    if (debugFlow.isLoggable(Level.FINE)) {
      debugFlow.fine("<<<< Doing add_and_flow() for " + name());
    }

    for (int i = 0; i < dataflow_ppts.length; i++) {
      PptTopLevel ppt = dataflow_ppts[i];
      //       if (debugFlow.isLoggable(Level.FINE)) {
      //        debugFlow.fine ("add_and_flow: A parent is " + ppt.name());
      //       }

      int[] transform = dataflow_transforms[i];
      Assert.assertTrue(transform.length == var_infos.length);

      // Map vt into the transformed tuple
      int ppt_num_vals = ppt.var_infos.length - ppt.num_static_constant_vars;
      Assert.assertTrue(ppt_num_vals == ppt.mbtracker.num_vars());
      Object[] vals = new Object[ppt_num_vals];
      int[] mods = new int[ppt_num_vals];
      Arrays.fill(mods, ValueTuple.MISSING_FLOW);
      for (int j = 0; j < transform.length; j++) {
        int tj = transform[j];
        if (tj == -1)
          continue;
        int this_value_index = var_infos[j].value_index;
        if (this_value_index == -1)
          continue; // is_static_constant
        int ppt_value_index = ppt.var_infos[tj].value_index;
        vals[ppt_value_index] = vt.vals[this_value_index];
        mods[ppt_value_index] = vt.mods[this_value_index];
      }
      ValueTuple ppt_vt = new ValueTuple(vals, mods);

      ppt.add(ppt_vt, count);
    }

  }

  /**
   * Add the sample to the invariants at this program point and any
   * child conditional program points, but do not flow the sample to
   * other related ppts.
   *
   * @param vt the set of values for this to see
   * @param count the number of samples that vt represents
   **/
  public List add(ValueTuple vt, int count) {
    // Doable, but commented out for efficiency
    // repCheck();

    // System.out.println("PptTopLevel " + name() + ": add " + vt);
    Assert.assertTrue(
      vt.size() == var_infos.length - num_static_constant_vars,
      name);

    //     if (debugFlow.isLoggable(Level.FINE)) {
    //       debugFlow.fine ("Add for " + this.name);
    //     }

    if (debugFlow.isLoggable(Level.FINE)) {
      debugFlow.fine("<<< Doing add for " + name());
      debugFlow.fine("    with vt " + vt.toString(this.var_infos));
    }

    if (values_num_samples == 0) {
      debugFlow.fine("  Instantiating views for the first time");
      instantiate_views_and_invariants();

      if (Global.debugInfer.isLoggable(Level.FINE)) {
        Global.debugInfer.fine("Instantiated views first time for " + this);
      }
    }

    if (Daikon.use_equality_optimization) {
      equality_view.add(vt, count);
    }
    instantiated_inv_cnt = invariant_cnt();
    instantiated_slice_cnt = views.size();

    values_num_samples += count;

    // For reasons I do not understand, in the "suppress" version of the
    // tests, calling mbtracker.add here causes (bad) diffs, but calling it
    // at the end of this routine is fine.  So for now, just call it at the
    // end of the routine.
    // // System.out.println("About to call ModBitTracker.add for " + name() + " <= " + vt.toString());
    // // mbtracker.add(vt, count);

    // System.out.println("About to call ValueSet.add for " + name() + " <= " + vt.toString());
    for (int i = 0; i < vt.vals.length; i++) {
      if (!vt.isMissing(i)) {
        ValueSet vs = value_sets[i];
        vs.add(vt.vals[i]);
        // System.out.println("ValueSet(" + i + ") now has " + vs.size() + " elements");
      } else {
        ValueSet vs = value_sets[i];
        // System.out.println("ValueSet(" + i + ") not added to, still has " + vs.size() + " elements");
      }
    }

    for (Iterator itor = views_iterator(); itor.hasNext();) {
      PptSlice view = (PptSlice) itor.next();
      view.add(vt, count);
      if (view.invs.size() == 0) {
        itor.remove();
        if (Global.debugInfer.isLoggable(Level.FINE)) {
          Global.debugInfer.fine(
            "add(ValueTulple,int): slice died: " + name() + view.varNames());
        }
      }
    }

    // Add to all the conditional ppts (not implemented in top down)
    if (false) {
      for (Iterator itor = cond_iterator(); itor.hasNext();) {
        PptConditional pptcond = (PptConditional) itor.next();
        pptcond.add(vt, count);
        // TODO: Check for no more invariants on pptcond?
      }
    }

    mbtracker.add(vt, count);

    return new ArrayList();
  }

  /**
   * Add the sample both to this point and to the global ppt (if
   * any).  Any invariants weakened at the global ppt are added to
   * the list of all weakened invariants.
   * @see #add_bottom_up
   **/
  public void add_global_bottom_up(ValueTuple vt, int count) {

    // If there is a global ppt
    if (global != null) {

      // Create an orig version of the sample and apply it to the global ppt
      ValueTuple orig_vt = transform_sample(global_transform_orig, vt);
      Set wset = global.add_bottom_up(orig_vt, count);

      // Create a post version of the sample and apply it to the global ppt
      ValueTuple post_vt = transform_sample(global_transform_post, vt);
      wset.addAll(global.add_bottom_up(post_vt, count));

      // Add all weakened invs that haven't already flowed to the list
      for (Iterator i = wset.iterator(); i.hasNext();) {
        Invariant inv = (Invariant) i.next();
        Assert.assertTrue(inv != null);
        if (!inv.flowed) {
          global_weakened_invs.add(inv);
          inv.flowed = true;
          if (Debug.logOn())
            inv.log("Added to list of invariants to flow");
        }
      }

      // If flowing immediately
      if (dkconfig_flow_globals_immed) {

        // Add all of the weakened invariants to each leaf
        for (Iterator i = Daikon.all_ppts.ppt_all_iterator(); i.hasNext();) {
          PptTopLevel ppt = (PptTopLevel) i.next();
          if (!ppt.ppt_name.isNumberedExitPoint())
            continue;
          for (Iterator j = global_weakened_invs.iterator(); j.hasNext();) {
            Invariant inv = (Invariant) j.next();
            add_weakened_global_inv(inv, global_transform_orig);
            add_weakened_global_inv(inv, global_transform_post);
          }
        }
        global_weakened_invs.clear();

      } else {

        // Add any invariants that have weakened since the last time this ppt
        // was processed to this ppt.
        add_weakened_global_invs();
      }
    }

    // Add the sample to this ppt
    add_bottom_up(vt, count);

    // check_vs_global();
  }

  /**
   * Add the sample to the equality sets, dynamic constants and
   * invariants at this program point.  This version is specific to
   * the bottom up processing mechanism.
   *
   * This routine also instantiates slices/invariants on the first
   * call for the ppt.
   *
   * @param vt the set of values for this to see
   * @param count the number of samples that vt represents
   *
   * @return the set of all invariants weakened or falsified by this sample
   **/
  public Set /* Invariant */
  add_bottom_up(ValueTuple vt, int count) {
    // Doable, but commented out for efficiency
    // repCheck();

    // System.out.println ("Processing samples at " + name());

    Assert.assertTrue(
      vt.size() == var_infos.length - num_static_constant_vars,
      name);

    // If there are conditional program points, add the sample there instead
    if (has_splitters()) {
      for (Iterator ii = splitters.iterator(); ii.hasNext();) {
        PptSplitter ppt_split = (PptSplitter) ii.next();
        ppt_split.add_bottom_up(vt, count);
      }
      if (Daikon.use_dataflow_hierarchy)
        return (null);
    }

    // If we are not using the hierarchy and this is a numbered exit, also
    // apply these values to the combined exit
    if (!Daikon.use_dataflow_hierarchy) {
      // System.out.println ("ppt_name = " + ppt_name);
      if (!(this instanceof PptConditional)
        && ppt_name.isNumberedExitPoint()) {
        PptTopLevel parent = Daikon.all_ppts.get(ppt_name.makeExit());
        if (parent != null) {
          // System.out.println ("parent is " + parent.name());
          parent.get_missingOutOfBounds(this, vt);
          parent.add_bottom_up(vt, count);
        }
      }
    }

    if (debugNISStats.isLoggable(Level.FINE))
      NIS.clear_stats();

    // Set of invariants weakened by this sample
    Set weakened_invs = new LinkedHashSet();

    // Instantiate slices and invariants if this is the first sample
    if (values_num_samples == 0) {
      debugFlow.fine("  Instantiating views for the first time");
      if (!Daikon.dkconfig_use_dynamic_constant_optimization)
        instantiate_views_and_invariants();
    }

    // Add the samples to all of the equality sets, breaking sets as required
    if (Daikon.use_equality_optimization) {
      weakened_invs.addAll(equality_view.add(vt, count));
      for (Iterator i = weakened_invs.iterator(); i.hasNext();)
        Assert.assertTrue(i.next() instanceof Invariant);
    }

    // Add samples to constants, adding new invariants as required
    if (Daikon.dkconfig_use_dynamic_constant_optimization) {
      if (constants == null)
        constants = new DynamicConstants(this);
      constants.add(vt, count);
    }

    instantiated_inv_cnt = invariant_cnt();
    instantiated_slice_cnt = views.size();

    if (debugInstantiate.isLoggable(Level.FINE) && values_num_samples == 0) {
      int slice1_cnt = 0;
      int slice2_cnt = 0;
      int slice3_cnt = 0;
      for (Iterator j = views_iterator(); j.hasNext();) {
        PptSlice slice = (PptSlice) j.next();
        if (slice instanceof PptSlice1)
          slice1_cnt++;
        else if (slice instanceof PptSlice2)
          slice2_cnt++;
        else if (slice instanceof PptSlice3)
          slice3_cnt++;
      }
      System.out.println("ppt " + name());
      debugInstantiate.fine("slice1 (" + slice1_cnt + ") slices");
      for (Iterator j = views_iterator(); j.hasNext();) {
        PptSlice slice = (PptSlice) j.next();
        if (slice instanceof PptSlice1)
          debugInstantiate.fine(
            " : "
              + slice.var_infos[0].name.name()
              + ": "
              + slice.var_infos[0].file_rep_type
              + ": "
              + slice.var_infos[0].rep_type
              + ": "
              + slice.var_infos[0].equalitySet.shortString());
        if (false) {
          for (int k = 0; k < slice.invs.size(); k++) {
            Invariant inv = (Invariant) slice.invs.get(k);
            debugInstantiate.fine("-- invariant " + inv.format());
          }
        }
      }
      debugInstantiate.fine("slice2 (" + slice2_cnt + ") slices");
      for (Iterator j = views_iterator(); j.hasNext();) {
        PptSlice slice = (PptSlice) j.next();
        if (slice instanceof PptSlice2)
          debugInstantiate.fine(
            " : "
              + slice.var_infos[0].name.name()
              + " : "
              + slice.var_infos[1].name.name());
      }
      debugInstantiate.fine("slice3 (" + slice3_cnt + ") slices");
      for (Iterator j = views_iterator(); j.hasNext();) {
        PptSlice slice = (PptSlice) j.next();
        if (slice instanceof PptSlice3)
          debugInstantiate.fine(
            " : "
              + slice.var_infos[0].name.name()
              + " : "
              + slice.var_infos[1].name.name()
              + " : "
              + slice.var_infos[2].name.name());
      }
    }

    values_num_samples += count;

    mbtracker.add(vt, count);

    for (int i = 0; i < vt.vals.length; i++) {
      if (!vt.isMissing(i)) {
        ValueSet vs = value_sets[i];
        vs.add(vt.vals[i]);
      } else {
        ValueSet vs = value_sets[i];
      }
    }

    // Add the sample to each slice
    for (Iterator i = views_iterator(); i.hasNext();) {
      PptSlice slice = (PptSlice) i.next();
      if (slice.invs.size() == 0)
        continue;
      weakened_invs.addAll(slice.add(vt, count));
    }

    // Create any newly unsuppressed invariants
    NIS.process_falsified_invs(this, vt);

    // Remove any falsified invariants.  Make a copy of the original slices
    // since NISuppressions will add new slices/invariants as others are
    // falsified.
    PptSlice[] slices =
      (PptSlice[]) views.values().toArray(new PptSlice[views.size()]);
    for (int i = 0; i < slices.length; i++) {
      slices[i].remove_falsified();
    }

    // Apply the sample to any invariants created by non-instantiating
    // suppressions.  This must happen before we remove slices without
    // invariants below.
    NIS.apply_samples(vt, count);

    // Remove slices from the list if all of their invariants have died
    for (Iterator itor = views_iterator(); itor.hasNext();) {
      PptSlice view = (PptSlice) itor.next();
      if (view.invs.size() == 0) {
        itor.remove();
        if (Global.debugInfer.isLoggable(Level.FINE))
          Global.debugInfer.fine(
            "add(ValueTulple,int): slice died: " + name() + view.varNames());
      }
    }

    // Add sample to all conditional ppts.  This is probably not fully
    // implemented in V3
    for (Iterator itor = cond_iterator(); itor.hasNext();) {
      PptConditional pptcond = (PptConditional) itor.next();
      pptcond.add(vt, count);
    }

    if (debugNISStats.isLoggable(Level.FINE))
      NIS.dump_stats(debugNISStats, this);

    return (weakened_invs);
  }

  /**
   * Adds a sample to each invariant in the list.  Returns the list of
   * weakened invariants.  This should only be called when the sample
   * has already been added to the slice containing each invariant.  Otherwise
   * the statistics kept in the slice will be incorrect.
   */
  public List /*Invariant */
  inv_add(List /*Invariant*/
  inv_list, ValueTuple vt, int count) {

    // Slices containing these invariants
    Set slices = new LinkedHashSet();

    // List of invariants weakened by this sample
    List weakened_invs = new ArrayList();

    // Loop through each invariant
    inv_loop : for (int i = 0; i < inv_list.size(); i++) {
      Invariant inv = (Invariant) inv_list.get(i);
      if (Debug.logDetail())
        inv.log("Processing in inv_add");

      // Skip falsified invariants (shouldn't happen)
      if (inv.is_false())
        continue;

      // Skip any invariants with a missing variable
      for (int j = 0; j < inv.ppt.var_infos.length; j++) {
        if (inv.ppt.var_infos[j].isMissing(vt))
          continue inv_loop;
      }

      // Add the slice containing this invariant to the set of slices
      slices.add(inv.ppt);

      // Get the values and add them to the invariant.
      InvariantStatus result = inv.add_sample(vt, count);

      if (result == InvariantStatus.FALSIFIED) {
        inv.falsify();
        weakened_invs.add(inv);
      } else if (result == InvariantStatus.WEAKENED) {
        weakened_invs.add(inv);
      }
    }

    return (weakened_invs);
  }

  /**
   * Gets any missing out of bounds variables from the specified ppt and
   * applies them to the matching variable in this ppt if the variable
   * is MISSING_NONSENSICAL.  The goal is to set the missing_array_bounds
   * flag only if it was missing in ppt on THIS sample.
   *
   * This could fail if missing_array_bounds was set on a previous sample
   * and the MISSING_NONSENSICAL flag is set for a different reason on
   * this sample.  This could happen with an array in an object.
   *
   * This implmeentation is also not particularly efficient and the
   * variables must match exactly.
   *
   * Missing out of bounds really needs to be implemented as a separate
   * flag in the missing bits.  That would clear up all of this mess.
   */
  public void get_missingOutOfBounds(PptTopLevel ppt, ValueTuple vt) {

    for (int ii = 0; ii < ppt.var_infos.length; ii++) {
      if (ppt.var_infos[ii].missingOutOfBounds()) {
        int mod = vt.getModified(ppt.var_infos[ii]);
        if (mod == ValueTuple.MISSING_NONSENSICAL)
          var_infos[ii].derived.missing_array_bounds = true;
      }
    }
  }

  /**
   * Returns whether or not the specified variable is dynamically constant.
   */
  public boolean is_constant(VarInfo v) {
    return ((constants != null) && constants.is_constant(v));
  }

  /**
   * Returns whether or not the specified variable is currently dynamically
   * constant, or was a dynamic constant at the beginning of constant
   * processing.
   */
  public boolean is_prev_constant(VarInfo v) {
    return (
      (constants != null)
        && (constants.is_constant(v) || constants.is_prev_constant(v)));
  }

  /**
   * Returns whether or not the specified variable has been missing
   * for all samples seen so far.
   */
  public boolean is_missing(VarInfo v) {
    return ((constants != null) && constants.is_missing(v));
  }

  /**
   * returns whether the specified variable is currently missing OR
   * was missing at the beginning of constants processing.
   **/
  public boolean is_prev_missing(VarInfo v) {
    return ((constants != null) && constants.is_prev_missing(v));
  }

  /**
   * Adds all global invariants that have been weakened since the last
   * time it was called to this ppt.  Resets the weakened index so
   * that we won't process the same invariants more than once
   */

  private void add_weakened_global_invs() {

    // invariants added
    Set flowed_invs = new LinkedHashSet();

    // Loop through each weakened invariant since the last time we were called
    for (int i = global_weakened_offset;
      i < global_weakened_invs.size();
      i++) {
      Invariant global_inv = (Invariant) global_weakened_invs.get(i);
      Assert.assertTrue(global_inv != null);

      // add via the orig transform
      Invariant f = add_weakened_global_inv(global_inv, global_transform_orig);
      if (f != null)
        flowed_invs.add(f);

      // add via the post transform
      f = add_weakened_global_inv(global_inv, global_transform_post);
      if (f != null)
        flowed_invs.add(f);
    }

    // Loop through each flowed invariant and remove it if it is NI suppressed
    // This must be done here, rather than when flowing them above, because
    // all invariants have to be flowed before correct checking can take
    // place (if a suppressee flows before a suppressor, the suppressor won't
    // be seen).
    for (Iterator i = flowed_invs.iterator(); i.hasNext();) {
      Invariant f = (Invariant) i.next();
      if (!f.copy_ok(f.ppt)) {
        if (Debug.logOn())
          f.log("removing " + f.format() + " - suppressed");
        f.ppt.invs.remove(f);
      }
    }

    global_weakened_offset = global_weakened_invs.size();

    // Put this ppt at the end of the list of offsets
    global_weakened_offsets.remove(this);
    global_weakened_offsets.add(this);

    // If all of the ppts have processed the invariants at the beginning
    // of the weakened list, remove those invariants
    Iterator it = global_weakened_offsets.iterator();
    PptTopLevel first = (PptTopLevel) it.next();
    if (first.global_weakened_offset > global_weakened_start_index) {
      debugGlobal.fine(
        "Removing flowed invs "
          + global_weakened_start_index
          + " to "
          + first.global_weakened_offset);
      debugGlobal.fine("First ppt = " + first.name());
      int cnt = first.global_weakened_offset - global_weakened_start_index;
      for (int i = global_weakened_start_index;
        i < first.global_weakened_offset;
        i++)
        global_weakened_invs.set(i, null);
      global_weakened_start_index = first.global_weakened_offset;
    }
  }

  /**
   * Adds the specified global invariant to this ppt using the specified
   * transform for its variables.  If the slice for the invariant does not
   * currently exist, it is added.
   * @return the invariant that was added (if any)
   */
  private Invariant add_weakened_global_inv(
    Invariant global_inv,
    int[] transform) {

    // Note that it is not necessary to check flowable here.  The invariant
    // will already exist at the lower ppt if it is unflowable.  The
    // only exception to that is LinearBinary and LinearTernary will
    // remove themselves from the lower point if their equations
    // match the global ppt when their equation becomes defined.  In that
    // case we SHOULD flow the invariant since it is still true at
    // the lower point.  In all other cases, there will be a local
    // version of the the invariant which will correctly stop the
    // flow.

    // Transform the invariants global variables to local ones.  If any
    PptSlice global_slice = global_inv.ppt;
    VarInfo[] vis = new VarInfo[global_slice.var_infos.length];
    for (int j = 0; j < vis.length; j++) {
      VarInfo v = global_slice.var_infos[j];
      vis[j] = var_infos[transform[v.varinfo_index]];
    }

    if (Debug.logOn())
      Debug.log(
        global_inv.getClass(),
        this,
        vis,
        "considering flowing " + global_inv.format() + " to " + name);

    // If any vars are not canonical, don't copy the invariant.  This
    // occurs if the equality sets at the global ppt are different
    // from the local one.  If the local equality set splits, we'll
    // get those invariants when copying.
    for (int j = 0; j < vis.length; j++) {
      if (!vis[j].isCanonical()) {
        if (Debug.logOn())
          Debug.log(
            global_inv.getClass(),
            this,
            vis,
            "not flowed, var " + vis[j].name.name() + "not leader");
        return (null);
      }
    }

    // We only need to flow invariants if this is a slice with all globals.
    // If there are any locals involved, we've already (or will) created
    // all of the invariants.  Locals can be involved because of equality
    // sets (the global is in an equality set with a local)
    if (!is_slice_global(vis)) {
      if (Debug.logOn())
        Debug.log(
          global_inv.getClass(),
          this,
          vis,
          "not flowed, slice not global");
      return (null);
    }

    // Order the variables for this ppt
    VarInfo[] vis_sorted = (VarInfo[]) vis.clone();
    Arrays.sort(vis_sorted, VarInfo.IndexComparator.getInstance());

    // Look up the local slice.  If the slice doesn't already exist,
    // don't create it.  It must be over dynamic constants.  When the slice
    // is created, dynamic constants will create exactly those invariants
    // that  don't exist at the  global level (ie, exactly those that
    // have flowed -- JHP 3/11/04: I think there are other reasons we can
    // have an empty slice.  This ought to check constants directly.
    PptSlice local_slice = findSlice(vis_sorted);
    if (local_slice == null) {
      if (Debug.logOn())
        Debug.log(
          global_inv.getClass(),
          this,
          vis,
          "not flowed, no local slice");
      return (null);
    }

    // build the global to local permute and use it to copy the invariant
    int[] permute = build_permute(vis, vis_sorted);
    Invariant local_inv = global_inv.clone_and_permute(permute);
    local_inv.clear_falsified();
    local_inv.ppt = local_slice;

    // We don't check for ni-suppression here, it is checked in the caller
    // after all invariants are flowed.

    // Add the invariant to the local slice unless it is already there
    if (!local_slice.contains_inv(local_inv)) {
      local_slice.addInvariant(local_inv);
      if (Debug.logOn()) {
        local_inv.log(
          "Added inv '"
            + local_inv
            + "' from global inv"
            + global_inv
            + " gfalse = "
            + global_inv.is_false());
      }
      return (local_inv);
    } else {
      if (Debug.logOn())
        global_inv.log("not flowed, already there");
      Assert.assertTrue(local_slice.invs.size() > 0);
      return (null);
    }
  }

  /** Returns the number of true invariants at this ppt. **/
  public int invariant_cnt() {

    int inv_cnt = 0;

    for (Iterator j = views_iterator(); j.hasNext();) {
      PptSlice slice = (PptSlice) j.next();
      inv_cnt += slice.invs.size();
    }
    return (inv_cnt);
  }

  /** Returns the number of slices that contain one or more constants. **/
  public int const_slice_cnt() {

    int const_cnt = 0;

    for (Iterator j = views_iterator(); j.hasNext();) {
      PptSlice slice = (PptSlice) j.next();
      for (int i = 0; i < slice.arity(); i++) {
        if (is_constant(slice.var_infos[i])) {
          const_cnt++;
          break;
        }
      }
    }
    return (const_cnt);
  }

  /** Returns the number of invariants that contain one or more constants. **/
  public int const_inv_cnt() {

    int const_cnt = 0;

    for (Iterator j = views_iterator(); j.hasNext();) {
      PptSlice slice = (PptSlice) j.next();
      for (int i = 0; i < slice.arity(); i++) {
        if (is_constant(slice.var_infos[i])) {
          const_cnt += slice.invs.size();
          break;
        }
      }
    }
    return (const_cnt);
  }

  static class Cnt {
    public int cnt = 0;
  }

  /**
   * Debug print to the specified logger information about each
   * invariant at this ppt
   */
  public void debug_invs(Logger log) {

    for (Iterator i = views_iterator(); i.hasNext();) {
      PptSlice slice = (PptSlice) i.next();
      log.fine("Slice: " + slice);
      for (Iterator j = slice.invs.iterator(); j.hasNext();) {
        Invariant inv = (Invariant) j.next();
        log.fine(
          "-- "
            + inv.format()
            + (NIS.is_suppressor(inv.getClass()) ? "[suppressor]" : "")
            + (inv.is_false() ? " [falsified]" : " "));
      }
    }
  }

  /**
   * Debug print to the specified logger information about each variable
   * in this ppt.  Currently only prints integer and float information
   * using the bound invariants
   */
  public void debug_unary_info(Logger log) {

    for (Iterator j = views_iterator(); j.hasNext();) {
      PptSlice slice = (PptSlice) j.next();
      if (!(slice instanceof PptSlice1))
        continue;
      LowerBound lb = null;
      LowerBoundFloat lbf = null;
      UpperBound ub = null;
      UpperBoundFloat ubf = null;
      for (int k = 0; k < slice.invs.size(); k++) {
        Invariant inv = (Invariant) slice.invs.get(k);
        if (inv instanceof LowerBound)
          lb = (LowerBound) inv;
        else if (inv instanceof LowerBoundFloat)
          lbf = (LowerBoundFloat) inv;
        else if (inv instanceof UpperBound)
          ub = (UpperBound) inv;
        else if (inv instanceof UpperBoundFloat)
          ubf = (UpperBoundFloat) inv;
      }
      if (lb != null)
        log.fine(
          lb.min()
            + " <= "
            + slice.var_infos[0].name.name()
            + " <= "
            + ub.max());
      else if (lbf != null)
        log.fine(
          lbf.min()
            + " <= "
            + slice.var_infos[0].name.name()
            + " <= "
            + ubf.max());
    }
  }

  /**
   * Returns how many invariants there are of each invariant class.  The
   * map is from the invariant class to an integer cnt of the number of
   * that class
   */
  public Map invariant_cnt_by_class() {

    Map inv_map = new LinkedHashMap();

    for (Iterator j = views_iterator(); j.hasNext();) {
      PptSlice slice = (PptSlice) j.next();
      for (int k = 0; k < slice.invs.size(); k++) {
        Invariant inv = (Invariant) slice.invs.get(k);
        Cnt cnt = (Cnt) inv_map.get(inv.getClass());
        if (cnt == null) {
          cnt = new Cnt();
          inv_map.put(inv.getClass(), cnt);
        }
        cnt.cnt++;
      }
    }

    return (inv_map);
  }

  /** Returns the number of slices at this ppt. **/
  public int slice_cnt() {
    return (views.size());
  }

  /**
   * Create all the derived variables.
   **/
  public void create_derived_variables() {
    if (debug.isLoggable(Level.FINE))
      debug.fine("create_derived_variables for " + name());

    int first_new = var_infos.length;
    // Make ALL of the derived variables.  The loop terminates
    // because derive() stops creating derived variables after some
    // depth.  Within the loop, [lower..upper) need deriving from.
    int lower = 0;
    int upper = var_infos.length;
    while (lower < upper) {
      Derivation[] ders = derive(lower, upper);
      lower = upper;
      upper += ders.length;

      VarInfo[] vis = new VarInfo[ders.length];
      for (int i = 0; i < ders.length; i++) {
        vis[i] = ders[i].getVarInfo();
      }
      if (Global.debugDerive.isLoggable(Level.FINE)) {
        for (int i = 0; i < ders.length; i++) {
          Global.debugDerive.fine("Derived " + vis[i].name.name());
        }
      }

      // Using addDerivedVariables(derivations) would add data too
      addVarInfos(vis);
    }
    Assert.assertTrue(lower == upper);
    Assert.assertTrue(upper == var_infos.length);

    if (debug.isLoggable(Level.FINE))
      debug.fine(
        "Done with create_derived_variables, " + var_infos.length + " vars");
  }

  /**
   * This function is called to jump-start processing; it creates all
   * the views (and thus candidate invariants), but does not check
   * those invariants.
   **/
  public void instantiate_views_and_invariants() {
    if (debug.isLoggable(Level.FINE))
      debug.fine("instantiate_views_and_invariants for " + name());

    // Now make all of the views (and thus candidate invariants)
    instantiate_views(0, var_infos.length);

    if (debug.isLoggable(Level.FINE))
      debug.fine("Done with instantiate_views_and_invariants");
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Creating invariants
  ///

  // I can't decide which loop it's more efficient to make the inner loop:
  // the loop over samples or the loop over slices.

  // slices_vector is a Vector of PptSlice; this routine does not modify it.
  // Maybe this should return the rejected views.
  public void addViews(Vector slices_vector) {
    if (slices_vector.isEmpty())
      return;

    // Don't modify the actual parameter
    slices_vector = (Vector) slices_vector.clone();

    // This might be a brand-new Slice, and instantiate_invariants for this
    // pass might not have come up with any invariants.
    for (Iterator itor = slices_vector.iterator(); itor.hasNext();) {
      PptSlice slice = (PptSlice) itor.next();
      if (slice.invs.size() == 0) {
        // removes the element from slices_vector
        itor.remove();
        if (Global.debugInfer.isLoggable(Level.FINE)) {
          Global.debugInfer.fine(
            "addViews: not adding " + slice + " due to no invariants");
        }
      }
    }

    addSlices(slices_vector);
  }

  /**
   * Add a collection of slices to the views of a PptTopLevel.
   **/
  private void addSlices(Collection slices) {
    for (Iterator i = slices.iterator(); i.hasNext();) {
      addSlice((PptSlice) i.next());
    }
  }

  // Given an array of VarInfos, return a List representing that array,
  // to be used as an index in the views hashtable.
  private List sliceIndex(VarInfo[] vis) {
    Integer[] a = new Integer[vis.length];
    for (int i = 0; i < vis.length; i++) {
      a[i] = new Integer(vis[i].varinfo_index);
    }
    return Arrays.asList(a);
  }

  /**
   * Add a single slice to the views variable
   **/
  public void addSlice(PptSlice slice) {

    // System.out.println ("Adding slice " + slice);
    // Throwable stack = new Throwable("debug traceback");
    // stack.fillInStackTrace();
    // stack.printStackTrace();

    // Make sure the slice doesn't already exist (should never happen)
    // Note that this can happen in top down due to flowing.  This is
    // probabably not correct, but not worth fixing
    PptSlice cslice = findSlice(slice.var_infos);
    if (Daikon.dkconfig_df_bottom_up && cslice != null) {
      System.out.println("Trying to add slice " + slice);
      System.out.println("but, slice " + cslice + " already exists");
      for (int i = 0; i < cslice.invs.size(); i++)
        System.out.println(" -- inv " + (Invariant) cslice.invs.get(i));
      Assert.assertTrue(cslice != null);
    }

    views.put(sliceIndex(slice.var_infos), slice);
    if (Debug.logOn())
      slice.log("Adding slice");
  }

  /**
   * Remove a slice from this PptTopLevel.
   **/
  public void removeSlice(PptSlice slice) {
    Object o = views.remove(sliceIndex(slice.var_infos));
    Assert.assertTrue(o != null);
  }

  /**
   * Remove a list of invariants
   */
  public void remove_invs(List /*Invariant*/
  rm_list) {
    for (Iterator i = rm_list.iterator(); i.hasNext();) {
      Invariant inv = (Invariant) i.next();
      inv.ppt.removeInvariant(inv);
    }
  }

  /**
   * Used to be a part of addViews, but for right now (Daikon V3) we
   * just want to set up all of the invariants, not actually feed them
   * data.
   **/
  private void __addViewsData(Vector slices_vector) {
    // use an array because iterating over it will be more efficient, I suspect.
    PptSlice[] slices =
      (PptSlice[]) slices_vector.toArray(new PptSlice[slices_vector.size()]);
    int num_slices = slices.length;

    // System.out.println("Adding views for " + name());
    // for (int i=0; i<slices.length; i++) {
    //   System.out.println("  View: " + slices[i].name);
    // }
    // values.dump();

    // System.out.println("Number of samples for " + name() + ": "
    //                    + values.num_samples()
    //                    + ", number of values: " + values.num_values());
    // If I recorded mod bits in value.ValueSet(), I could use it here instead.
    //      for (Iterator vt_itor = values.sampleIterator(); vt_itor.hasNext(); ) {
    //        VarValuesOrdered.ValueTupleCount entry = (VarValuesOrdered.ValueTupleCount) vt_itor.next();
    //        ValueTuple vt = entry.value_tuple;
    //        int count = entry.count;
    //        for (int i=0; i<num_slices; i++) {
    //          // System.out.println("" + slices[i] + " .add(" + vt + ", " + count + ")");
    //          slices[i].add(vt, count);
    //        }
    //        if (views_to_remove_deferred.size() > 0) {
    //          // Inefficient, but easy to code.
    //          Assert.assertTrue(slices_vector.containsAll(views_to_remove_deferred));
    //          slices_vector.removeAll(views_to_remove_deferred);
    //          views_to_remove_deferred.clear();
    //          if (slices_vector.size() == 0)
    //            break;
    //          slices = (PptSlice[]) slices_vector.toArray(new PptSlice[0]);
    //          num_slices = slices.length;
    //        }
    //      }
  }

  public void removeView(Ppt slice) {
    // System.out.println("removeView " + slice.name() + " " + slice);
    boolean removed = viewsAsCollection().remove(slice);
    Assert.assertTrue(removed);
  }

  // I've decided that views will contain only slices, which allows for
  // dramatic speedups in finding functions

  // The nouns "view" and "slice" are putatively different.  Slices
  // limit the variables but examine all data.  Views may ignore data,
  // etc.  In practive, getView always returns a slice anyway (see
  // comments on class daikon.Ppt).

  /**
   * Typically one should use the dynamic_constant or canBeMissing slots,
   * which cache the invariants of most interest, instead of this function.
   **/
  public PptSlice1 getView(VarInfo vi) {
    // This seems to do the same thing as findSlice(vi)

    return findSlice(vi);

    //      for (Iterator itor = views_iterator(); itor.hasNext(); ) {
    //        PptSlice slice = (PptSlice) itor.next();
    //        if ((slice.arity() == 1) && slice.usesVar(vi))
    //          return (PptSlice1) slice;
    //      }
    //      return null;
  }

  /**
   * Typically one should use the equal_to slot, which caches the
   * invariants of most interest, instead of this function.
   **/
  public PptSlice2 getView(VarInfo vi1, VarInfo vi2) {
    // This seems to do the same thing as findSlice(vi1, vi2)...

    return findSlice(vi1, vi2);

    //      for (Iterator itor = views_iterator(); itor.hasNext(); ) {
    //        PptSlice slice = (PptSlice) itor.next();
    //        if ((slice.arity() == 2) && slice.usesVar(vi1) && slice.usesVar(vi2))
    //          return (PptSlice2) slice;
    //      }
    //      return null;
  }

  // A slice is a specific kind of view, but we don't call this
  // findView because it doesn't find an arbitrary view.
  /**
   * findSlice can return null if the slice doesn't exist.  That can happen
   * if there are no true invariants over the set of variables -- when the
   * last invariant is removed, so is the slice.
   *
   * When one is looking for a particular invariant, typically one should
   * use the dynamic_constant or canBeMissing slots, which cache the
   * invariants of most interest, instead of calling function to get the
   * slice and then looking for the invariant in the slice.
   **/
  public PptSlice1 findSlice(VarInfo v) {
    return (PptSlice1) findSlice(new VarInfo[] { v });
    //      for (Iterator itor = views_iterator() ; itor.hasNext() ; ) {
    //        PptSlice view = (PptSlice) itor.next();
    //        if ((view.arity() == 1) && (v == view.var_infos[0]))
    //          return (PptSlice1) view;
    //      }
    //      return null;
  }

  /**
   * findSlice can return null if the slice doesn't exist.  That can happen
   * if there are no true invariants over the set of variables -- when the
   * last invariant is removed, so is the slice.
   *
   * When one is looking for a particular invariant, typically one should
   * use the dynamic_constant or canBeMissing slots, which cache the
   * invariants of most interest, instead of calling function to get the
   * slice and then looking for the invariant in the slice.
   **/
  public PptSlice2 findSlice(VarInfo v1, VarInfo v2) {
    Assert.assertTrue(v1.varinfo_index <= v2.varinfo_index);
    return (PptSlice2) findSlice(new VarInfo[] { v1, v2 });
    //      for (Iterator itor = views_iterator() ; itor.hasNext() ; ) {
    //        PptSlice view = (PptSlice) itor.next();
    //        if ((view.arity() == 2)
    //            && (v1 == view.var_infos[0])
    //            && (v2 == view.var_infos[1]))
    //          return (PptSlice2) view;
    //      }
    //      return null;
  }

  /**
   * Like findSlice, but it is not required that the variables be supplied
   * in order of varinfo_index.
   **/
  public PptSlice2 findSlice_unordered(VarInfo v1, VarInfo v2) {
    // Assert.assertTrue(v1.varinfo_index != v2.varinfo_index);
    if (v1.varinfo_index < v2.varinfo_index) {
      return findSlice(v1, v2);
    } else {
      return findSlice(v2, v1);
    }
  }

  /**
   * findSlice can return null if the slice doesn't exist.  That can happen
   * if there are no true invariants over the set of variables -- when the
   * last invariant is removed, so is the slice.
   *
   * When one is looking for a particular invariant, typically one should
   * use the dynamic_constant or canBeMissing slots, which cache the
   * invariants of most interest, instead of calling function to get the
   * slice and then looking for the invariant in the slice.
   **/
  public PptSlice3 findSlice(VarInfo v1, VarInfo v2, VarInfo v3) {
    Assert.assertTrue(v1.varinfo_index <= v2.varinfo_index);
    Assert.assertTrue(v2.varinfo_index <= v3.varinfo_index);
    return (PptSlice3) findSlice(new VarInfo[] { v1, v2, v3 });
    //      for (Iterator itor = views_iterator() ; itor.hasNext() ; ) {
    //        PptSlice view = (PptSlice) itor.next();
    //        if ((view.arity() == 3)
    //            && (v1 == view.var_infos[0])
    //            && (v2 == view.var_infos[1])
    //            && (v3 == view.var_infos[2]))
    //          return (PptSlice3) view;
    //      }
    //      return null;
  }

  /**
   * Like findSlice, but it is not required that the variables be supplied
   * in order of varinfo_index.
   **/
  public PptSlice3 findSlice_unordered(VarInfo v1, VarInfo v2, VarInfo v3) {
    // bubble sort is easier than 3 levels of if-then-else
    VarInfo tmp;
    if (v1.varinfo_index > v2.varinfo_index) {
      tmp = v2;
      v2 = v1;
      v1 = tmp;
    }
    if (v2.varinfo_index > v3.varinfo_index) {
      tmp = v3;
      v3 = v2;
      v2 = tmp;
    }
    if (v1.varinfo_index > v2.varinfo_index) {
      tmp = v2;
      v2 = v1;
      v1 = tmp;
    }
    return (PptSlice3) findSlice(v1, v2, v3);
  }

  /**
   * Find a pptSlice without an assumed ordering.
   **/
  public PptSlice findSlice_unordered(VarInfo[] vis) {
    switch (vis.length) {
      case 1 :
        return findSlice(vis[0]);
      case 2 :
        return findSlice_unordered(vis[0], vis[1]);
      case 3 :
        return findSlice_unordered(vis[0], vis[1], vis[2]);
      default :
        throw new RuntimeException("Bad length " + vis.length);
    }
  }

  /**
   * Find a pptSlice with an assumed ordering.
   **/
  public PptSlice findSlice(VarInfo[] vis) {
    if (vis.length > 3) {
      throw new RuntimeException("Bad length " + vis.length);
    }
    return (PptSlice) views.get(sliceIndex(vis));
  }

  public int indexOf(String varname) {
    for (int i = 0; i < var_infos.length; i++) {
      if (var_infos[i].name.name().equals(varname)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the invariant in the slice specified by vis that matches the
   * specified class.  If the slice or the invariant does not exist, returns
   * null.
   */
  public Invariant find_inv_by_class(VarInfo[] vis, Class cls) {

    PptSlice slice = findSlice(vis);
    if (slice == null)
      return (null);
    return slice.find_inv_by_class(cls);
  }

  /**
   * Returns the VarInfo with the specified name.  Null if the name is
   * not found
   */
  public VarInfo find_var_by_name(String varname) {
    int i = indexOf(varname);
    if (i == -1)
      return (null);
    else
      return (var_infos[i]);
  }

  /**
   * Looks up the slice for v1.  If the slice does not exist, one is
   * created (but not added into the list of slices for this ppt).
   */
  public PptSlice get_temp_slice(VarInfo v) {

    PptSlice slice = findSlice(v);
    if (slice == null)
      slice = new PptSlice1(this, v);

    return (slice);
  }

  /**
   * Looks up the slice for v1 and v2.  They do not have to be
   * in order.  If the slice does not exist, one  is created (but
   * not added into the list of slices for this ppt).
   */
  public PptSlice get_temp_slice(VarInfo v1, VarInfo v2) {

    PptSlice slice = findSlice_unordered(v1, v2);
    if (slice == null) {
      if (v1.varinfo_index <= v2.varinfo_index)
        slice = new PptSlice2(this, v1, v2);
      else
        slice = new PptSlice2(this, v2, v1);
    }

    return (slice);
  }

  /**
   * If the proto invariant is true over the specified variable returns
   * DiscardInfo indicating that the proto invariant implies imp_inv.
   * Otherwise returns null
   */
  public DiscardInfo check_implied(
    Invariant imp_inv,
    VarInfo v,
    Invariant proto) {

    // If there is no proto invariant, we can't look for it.  This happens
    // if the invariant is not enabled.
    if (proto == null)
      return (null);

    // Get the slice and instantiate the possible antecedent oer it
    PptSlice slice = get_temp_slice(v);
    Invariant antecedent_inv = proto.instantiate(slice);
    if (antecedent_inv == null)
      return (null);

    // Check to see if the antecedent is true
    if (slice.is_inv_true(antecedent_inv))
      return new DiscardInfo(
        imp_inv,
        DiscardCode.obvious,
        "Implied by " + antecedent_inv.format());

    return (null);
  }

  /**
   * If the proto invariant is true over the leader of the specified
   * variable returns DiscardInfo indicating that the proto invariant
   * implies imp_inv.  Otherwise returns null
   */
  public DiscardInfo check_implied_canonical(
    Invariant imp_inv,
    VarInfo v,
    Invariant proto) {

    VarInfo leader = v.canonicalRep();

    DiscardInfo di = check_implied(imp_inv, leader, proto);
    if (di == null)
      return (null);

    // Build a new discardString that includes the variable equality
    String reason = di.discardString();
    if (leader != v)
      reason += " and (" + leader + "==" + v + ")";

    return new DiscardInfo(imp_inv, DiscardCode.obvious, reason);
  }
  /**
   * If the proto invariant is true over the specified variables returns
   * DiscardInfo indicating that the proto invariant implies imp_inv.
   * Otherwise returns null
   */
  public DiscardInfo check_implied(
    Invariant imp_inv,
    VarInfo v1,
    VarInfo v2,
    Invariant proto) {

    // If there is no proto invariant, we can't look for it.  This happens
    // if the invariant is not enabled.
    if (proto == null)
      return (null);

    // Get the slice and instantiate the possible antecedent oer it
    PptSlice slice = get_temp_slice(v1, v2);
    Invariant antecedent_inv = proto.instantiate(slice);
    if (antecedent_inv == null)
      return (null);

    // Permute the antecedent if necessary
    if (v1.varinfo_index > v2.varinfo_index)
      antecedent_inv = antecedent_inv.permute(permute_swap);

    // Check to see if the antecedent is true
    if (slice.is_inv_true(antecedent_inv))
      return new DiscardInfo(
        imp_inv,
        DiscardCode.obvious,
        "Implied by " + antecedent_inv.format());

    return (null);
  }

  public boolean check_implied(
    DiscardInfo di,
    VarInfo v1,
    VarInfo v2,
    Invariant proto) {

    DiscardInfo di2 = check_implied(di.inv, v1, v2, proto);
    if (di2 == null)
      return (false);

    di.add_implied(di2.discardString());
    return (true);
  }

  /**
   * If the proto invariant is true over the leader of each specified
   * variables returns DiscardInfo indicating that the proto invariant
   * implies imp_inv.  Otherwise returns null
   */
  public DiscardInfo check_implied_canonical(
    Invariant imp_inv,
    VarInfo v1,
    VarInfo v2,
    Invariant proto) {

    VarInfo leader1 = v1.canonicalRep();
    VarInfo leader2 = v2.canonicalRep();

    DiscardInfo di = check_implied(imp_inv, leader1, leader2, proto);
    if (di == null)
      return (null);

    // If the variables match the leader, the current reason is good
    if ((leader1 == v1) && (leader2 == v2))
      return (di);

    // Build a new discardString that includes the variable equality
    String reason = di.discardString();
    if (leader1 != v1)
      reason += " and (" + leader1 + "==" + v1 + ")";
    if (leader2 != v2)
      reason += " and (" + leader2 + "==" + v2 + ")";

    return new DiscardInfo(imp_inv, DiscardCode.obvious, reason);
  }

  public boolean check_implied_canonical(
    DiscardInfo di,
    VarInfo v1,
    VarInfo v2,
    Invariant proto) {

    DiscardInfo di2 = check_implied_canonical(di.inv, v1, v2, proto);
    if (di2 == null)
      return (false);

    di.add_implied(di2.discardString());
    return (true);
  }

  /**
   * Returns whether or not v1 is a subset of v2.
   */
  public boolean is_subset(VarInfo v1, VarInfo v2) {

    // Find the slice for v1 and v2.  If no slice exists, create it,
    // but don't add it to the slices for this ppt.  It only exists
    // as a temporary home for the invariant we are looking for below.
    PptSlice slice = get_temp_slice(v1, v2);

    // Create the invariant we are looking for
    Invariant inv = null;
    if ((v1.rep_type == ProglangType.INT_ARRAY)) {
      Assert.assertTrue(v2.rep_type == ProglangType.INT_ARRAY);
      inv = SubSet.get_proto().instantiate(slice);
    } else if (v1.rep_type == ProglangType.DOUBLE_ARRAY) {
      Assert.assertTrue(v2.rep_type == ProglangType.DOUBLE_ARRAY);
      inv = SubSetFloat.get_proto().instantiate(slice);
    }

    if (inv == null)
      return (false);

    // If the varinfos are out of order swap
    if (v1.varinfo_index > v2.varinfo_index)
      inv = inv.permute(permute_swap);

    // Look for the invariant
    return (slice.is_inv_true(inv));
  }

  /**
   * Returns whether or not the specified variables are equal (ie,
   * an equality invariant exists between them)
   */
  public boolean is_equal(VarInfo v1, VarInfo v2) {

    // Find the slice for v1 and v2.  If the slice doesn't exist,
    // the variables can't be equal
    PptSlice slice = findSlice_unordered(v1, v2);
    if (slice == null)
      return (false);

    // Get a prototype of the invariant we are looking for
    Invariant proto = null;
    if (v1.rep_type.isScalar()) {
      Assert.assertTrue(v2.rep_type.isScalar());
      proto = IntEqual.get_proto();
    } else if (v1.rep_type.isFloat()) {
      Assert.assertTrue(v2.rep_type.isFloat());
      proto = FloatEqual.get_proto();
    } else if (v1.rep_type == ProglangType.STRING) {
      Assert.assertTrue(v2.rep_type == ProglangType.STRING);
      proto = StringEqual.get_proto();
    } else if ((v1.rep_type == ProglangType.INT_ARRAY)) {
      Assert.assertTrue(v2.rep_type == ProglangType.INT_ARRAY);
      proto = SeqSeqIntEqual.get_proto();
    } else if (v1.rep_type == ProglangType.DOUBLE_ARRAY) {
      Assert.assertTrue(v2.rep_type == ProglangType.DOUBLE_ARRAY);
      proto = SeqSeqFloatEqual.get_proto();
    } else if ((v1.rep_type == ProglangType.STRING_ARRAY)) {
      Assert.assertTrue(v2.rep_type == ProglangType.STRING_ARRAY);
      proto = SeqSeqStringEqual.get_proto();
    } else {
      Assert.assertTrue(false, "unexpected type " + v1.rep_type);
    }
    Assert.assertTrue(proto != null);
    Assert.assertTrue(proto.valid_types(slice.var_infos));

    // Return whether or not the invariant is true in the slice
    Invariant inv = proto.instantiate(slice);
    if (inv == null)
      return (false);
    return (slice.is_inv_true(inv));
  }

  /**
   * Returns true if (v1+v1_shift) <= (v2+v2_shift) is known
   * to be true.  Returns false otherwise.  Integers only.
   */
  public boolean is_less_equal(
    VarInfo v1,
    int v1_shift,
    VarInfo v2,
    int v2_shift) {

    Assert.assertTrue(v1.ppt == this);
    Assert.assertTrue(v2.ppt == this);
    Assert.assertTrue(v1.file_rep_type.isIntegral());
    Assert.assertTrue(v2.file_rep_type.isIntegral());

    Invariant inv = null;
    PptSlice slice = null;
    if (v1.varinfo_index <= v2.varinfo_index) {
      slice = findSlice(v1, v2);
      if (slice != null) {
        if (v1_shift <= v2_shift) {
          inv = IntLessEqual.get_proto().instantiate(slice);
        } else if (v1_shift == (v2_shift + 1)) {
          inv = IntLessThan.get_proto().instantiate(slice);
        } else { //  no invariant over v1 and v2 shows ((v1 + 2) <= v2)
        }
      }
    } else {
      slice = findSlice(v2, v1);
      if (slice != null) {
        if (v1_shift <= v2_shift) {
          inv = IntGreaterEqual.get_proto().instantiate(slice);
        } else if (v1_shift == (v2_shift + 1)) {
          inv = IntGreaterThan.get_proto().instantiate(slice);
        } else { //  no invariant over v1 and v2 shows ((v2 + 2) <= v1)
        }
      }
    }

    boolean found = (inv != null) && slice.is_inv_true(inv);
    if (false) {
      Fmt.pf(
        "Looking for %s [%s] <= %s [%s] in ppt %s",
        v1.name.name(),
        "" + v1_shift,
        v2.name.name(),
        "" + v2_shift,
        this.name());
      Fmt.pf(
        "Searched for invariant %s, found = %s",
        (inv == null) ? "null" : inv.format(),
        "" + found);
    }
    return (found);
  }

  /**
   * Returns true if v1 is known to be a subsequence of v2.  This
   * is true if the subsequence invariant exists or if it it
   * suppressed
   */
  public boolean is_subsequence(VarInfo v1, VarInfo v2) {

    // Find the slice for v1 and v2.  If no slice exists, create it,
    // but don't add it to the slices for this ppt.  It only exists
    // as a temporary home for the invariant we are looking for below.
    PptSlice slice = get_temp_slice(v1, v2);

    // Create the invariant we are looking for.
    Invariant inv = null;
    if ((v1.rep_type == ProglangType.INT_ARRAY)) {
      Assert.assertTrue(v2.rep_type == ProglangType.INT_ARRAY);
      inv = SubSequence.get_proto().instantiate(slice);
    } else if (v1.rep_type == ProglangType.DOUBLE_ARRAY) {
      Assert.assertTrue(v2.rep_type == ProglangType.DOUBLE_ARRAY);
      inv = SubSequenceFloat.get_proto().instantiate(slice);
    } else {
      Assert.assertTrue(false, "unexpected type " + v1.rep_type);
    }

    if (inv == null)
      return (false);

    // If the varinfos are out of order swap
    if (v1.varinfo_index > v2.varinfo_index)
      inv = inv.permute(permute_swap);

    return (slice.is_inv_true(inv));
  }

  /**
   * Returns true if varr is empty.  Supports ints, doubles, and
   * strings.
   */
  public boolean is_empty(VarInfo varr) {

    // Find the slice for varr.  If no slice exists, create it, but
    // don't add it to the slices for this ppt.  It only exists as a
    // temporary home for the invariant we are looking for below.
    PptSlice slice = findSlice(varr);
    if (slice == null) {
      slice = new PptSlice1(this, varr);
    }

    // Create a one of invariant with an empty array as its only
    // value.
    Invariant inv = null;
    if ((varr.rep_type == ProglangType.INT_ARRAY)) {
      OneOfSequence oos =
        (OneOfSequence) OneOfSequence.get_proto().instantiate(slice);
      if (oos != null) {
        long[][] one_of = new long[1][];
        one_of[0] = new long[0];
        oos.set_one_of_val(one_of);
        inv = oos;
      }
    } else if (varr.rep_type == ProglangType.DOUBLE_ARRAY) {
      OneOfFloatSequence oos =
        (OneOfFloatSequence) OneOfFloatSequence.get_proto().instantiate(slice);
      if (oos != null) {
        double[][] one_of = new double[1][];
        one_of[0] = new double[0];
        oos.set_one_of_val(one_of);
        inv = oos;
      }
    } else if (varr.rep_type == ProglangType.STRING_ARRAY) {
      OneOfStringSequence oos =
        (OneOfStringSequence) OneOfStringSequence.get_proto().instantiate(
          slice);
      if (oos != null) {
        String[][] one_of = new String[1][];
        one_of[0] = new String[0];
        oos.set_one_of_val(one_of);
        inv = oos;
      }
    }

    if (inv == null)
      return (false);

    return (slice.is_inv_true(inv));
  }

  // At present, this needs to occur after deriving variables, because
  // I haven't integrated derivation and inference yet.
  // (This function doesn't exactly belong in this part of the file.)

  // Should return a list of the views created, perhaps.

  /**
   * Install views and the invariants.  We create NO views over static
   * constant variables, but everything else is fair game.  We don't
   * create views over variables which have a higher (controlling)
   * view.  This function does NOT cause invariants over the new views
   * to be checked (but it does create invariants).  The installed
   * views and invariants will all have at least one element with
   * index i such that vi_index_min <= i < vi_index_limit.  (However,
   * we also assume that vi_index_limit == var_infos.length.)
   **/

  // Note that some slightly inefficient code has been added to aid
  // in debugging.  When creating binary and ternary views and debugging
  // is on, the outer loops will not terminate prematurely on innapropriate
  // (ie, non-canonical) variables.  This allows explicit debug statements
  // for each possible combination, simplifying determining why certain
  // slices were not created.

  private void instantiate_views(int vi_index_min, int vi_index_limit) {
    if (Global.debugInfer.isLoggable(Level.FINE))
      Global.debugInfer.fine(
        "instantiate_views: "
          + this.name
          + ", vi_index_min="
          + vi_index_min
          + ", vi_index_limit="
          + vi_index_limit
          + ", var_infos.length="
          + var_infos.length);

    // This test prevents instantiate views for variables one at a time.
    Assert.assertTrue(var_infos.length == vi_index_limit);

    if (vi_index_min == vi_index_limit)
      return;

    // used only for debugging
    int old_num_vars = var_infos.length;
    int old_num_views = views.size();
    boolean debug_on = debug.isLoggable(Level.FINE);

    /// 1. all unary views

    // Unary slices/invariants.
    Vector unary_views = new Vector(vi_index_limit - vi_index_min);
    for (int i = vi_index_min; i < vi_index_limit; i++) {
      VarInfo vi = var_infos[i];

      if (Debug.logOn())
        Debug.log(
          getClass(),
          this,
          Debug.vis(vi),
          " Instantiate Slice, ok=" + is_slice_ok(vi));
      //System.out.println (" Instantiate Slice " + name() + " var = "
      //                    + vi.name.name() + "ok=" + is_slice_ok (vi));
      if (!is_slice_ok(vi))
        continue;

      // Eventually, add back in this test as "if constant and no
      // comparability info exists" then continue.
      // if (vi.isStaticConstant()) continue;
      PptSlice1 slice1 = new PptSlice1(this, vi);
      slice1.instantiate_invariants();
      if (Debug.logOn() || debug_on)
        Debug.log(debug, getClass(), slice1, "Created unary slice");
      unary_views.add(slice1);
    }
    addViews(unary_views);
    unary_views = null;

    /// 2. all binary views

    // Binary slices/invariants.
    Vector binary_views = new Vector();
    for (int i1 = 0; i1 < vi_index_limit; i1++) {
      VarInfo var1 = var_infos[i1];
      if (!var1.isCanonical() && !(Debug.logOn() || debug_on)) {
        continue;
      }

      // Eventually, add back in this test as "if constant and no
      // comparability info exists" then continue.
      // if (var1.isStaticConstant()) continue;
      boolean target1 = (i1 >= vi_index_min) && (i1 < vi_index_limit);
      int i2_min = (target1 ? i1 : Math.max(i1, vi_index_min));
      for (int i2 = i2_min; i2 < vi_index_limit; i2++) {
        VarInfo var2 = var_infos[i2];

        if (!var1.isCanonical()) {
          if (Debug.logOn() || debug_on)
            Debug.log(
              debug,
              getClass(),
              this,
              Debug.vis(var1, var2),
              "Binary slice not created, var1 is not a leader");
          continue;
        }
        if (!var2.isCanonical()) {
          if (Debug.logOn() || debug_on)
            Debug.log(
              debug,
              getClass(),
              this,
              Debug.vis(var1, var2),
              "Binary slice not created, var2 is not a leader");
          continue;
        }

        // This is commented out because if one var is an array and the
        // other is not, this will indicate that the two vars are not
        // compatible.  This causes us to miss seemingly valid elementwise
        // invariants
        // if (!var1.compatible(var2)) {
        //  if (Debug.logOn() || debug_on)
        //    Debug.log (debug, getClass(), this, new VarInfo[] {var1, var2},
        //               "Binary slice not created, vars not compatible");
        //  continue;
        //}

        // Eventually, add back in this test as "if constant and no
        // comparability info exists" then continue.
        // if (var2.isStaticConstant()) continue;
        if (!is_slice_ok(var1, var2)) {
          if (Debug.logOn() || debug_on)
            Debug.log(
              debug,
              getClass(),
              this,
              Debug.vis(var1, var2),
              "Binary slice not created, is_slice_ok == false");
          continue;
        }
        PptSlice2 slice2 = new PptSlice2(this, var1, var2);
        if (Debug.logOn() || debug_on)
          Debug.log(debug, getClass(), slice2, "Creating binary slice");

        slice2.instantiate_invariants();
        binary_views.add(slice2);
      }
    }
    addViews(binary_views);
    binary_views = null;

    // 3. all ternary views
    if (Global.debugInfer.isLoggable(Level.FINE)) {
      Global.debugInfer.fine("Trying ternary slices for " + this.name());
    }

    Vector ternary_views = new Vector();
    for (int i1 = 0; i1 < vi_index_limit; i1++) {
      VarInfo var1 = var_infos[i1];
      if (!var1.isCanonical() && !(Debug.logOn() || debug_on))
        continue;

      // Eventually, add back in this test as "if constant and no
      // comparability info exists" then continue.
      // if (var1.isStaticConstant()) continue;
      // For now, only ternary invariants not involving any arrays
      if (var1.rep_type.isArray() && (!Debug.logOn() || debug_on))
        continue;

      boolean target1 = (i1 >= vi_index_min) && (i1 < vi_index_limit);
      for (int i2 = i1; i2 < vi_index_limit; i2++) {
        VarInfo var2 = var_infos[i2];
        if (!var2.isCanonical() && !(Debug.logOn() || debug_on))
          continue;

        // Eventually, add back in this test as "if constant and no
        // comparability info exists" then continue.
        // if (var2.isStaticConstant()) continue;
        // For now, only ternary invariants not involving any arrays
        if (var2.rep_type.isArray() && !(Debug.logOn() || debug_on))
          continue;

        boolean target2 = (i2 >= vi_index_min) && (i2 < vi_index_limit);
        int i3_min = ((target1 || target2) ? i2 : Math.max(i2, vi_index_min));
        for (int i3 = i3_min; i3 < vi_index_limit; i3++) {
          Assert.assertTrue(
            ((i1 >= vi_index_min) && (i1 < vi_index_limit))
              || ((i2 >= vi_index_min) && (i2 < vi_index_limit))
              || ((i3 >= vi_index_min) && (i3 < vi_index_limit)));
          Assert.assertTrue((i1 <= i2) && (i2 <= i3));
          VarInfo var3 = var_infos[i3];

          if (!is_slice_ok(var1, var2, var3))
            continue;

          PptSlice3 slice3 = new PptSlice3(this, var1, var2, var3);
          slice3.instantiate_invariants();
          if (Debug.logOn() || debug_on)
            Debug.log(debug, getClass(), slice3, "Created Ternary Slice");
          ternary_views.add(slice3);
        }
      }
    }
    addViews(ternary_views);

    if (debug.isLoggable(Level.FINE))
      debug.fine(views.size() - old_num_views + " new views for " + name());

    // Remove any invariants that are suppressed.  This needs to occur
    // here rather than during instantiate because we need to have created
    // all possible suppressors.
    NIS.remove_suppressed_invs(this);

    // This method didn't add any new variables.
    Assert.assertTrue(old_num_vars == var_infos.length);
    repCheck();
  }

  /**
   * Returns whether or not the specified slice should be created.
   */
  public boolean is_slice_ok(VarInfo[] vis, int arity) {
    if (arity == 1)
      return (is_slice_ok(vis[0]));
    else if (arity == 2)
      return (is_slice_ok(vis[0], vis[1]));
    else
      return (is_slice_ok(vis[0], vis[1], vis[2]));
  }

  /**
   * Returns whether or not the specified unary slice should be
   * created.  The variable must be a leader, not a constant, and
   * not always missing.
   */
  public boolean is_slice_ok(VarInfo var1) {

    if (Daikon.dkconfig_use_dynamic_constant_optimization && constants == null)
      return (false);
    if (is_constant(var1))
      return (false);
    if (is_missing(var1))
      return (false);
    if (!var1.isCanonical())
      return (false);

    return (true);
  }

  /**
   * Returns whether or not the specified binary slice should be created.
   * Checks to sinsure that var1 and var2 are not both constants and
   * if they are in the same equality set, that there are at least 2
   * variables in the equality set.  Also makes sure that neither var1
   * or var2 is always missing.
   */
  public boolean is_slice_ok(VarInfo var1, VarInfo var2) {

    // Both vars must be leaders
    if (!var1.isCanonical() || !var2.isCanonical())
      return (false);

    // Check to see if the new slice would be over all constants
    if (is_constant(var1) && is_constant(var2))
      return (false);

    // Each variable must not be always missing
    if (is_missing(var1) || is_missing(var2))
      return (false);

    // Don't create a slice with the same variables if the equality
    // set only contains 1 variable
    // This is not turned on for now since suppressions need invariants
    // of the form a == a even when a is the only item in the set.
    if (false) {
      if ((var1 == var2) && (var1.get_equalitySet_size() == 1))
        return (false);
    }

    return (true);
  }

  /**
   * Returns whether or not the specified ternary slice should be created.
   * The slice should not be created if any of the following are true
   *    - Any var is always missing
   *    - Any var is not canonical
   *    - Any var is an array
   *    - Any of the vars are not comparable with the others
   *    - All of the vars are constants
   *    - Any var is not (integral or float)
   *    - Each var is the same and its equality set has only two variables
   *    - Two of the vars are the same and its equality has only one variable
   *      (this last one is currently disabled as x = func(x,y) might still
   *      be interesting even if x is the same.
   */
  public boolean is_slice_ok(VarInfo v1, VarInfo v2, VarInfo v3) {

    Debug dlog = null;
    if (Debug.logOn() || debug.isLoggable(Level.FINE))
      dlog = new Debug(getClass(), this, Debug.vis(v1, v2, v3));

    // Each variable must not be always missing
    if (is_missing(v1) || is_missing(v2) || is_missing(v3))
      return (false);

    // At least one variable must not be a constant
    if (is_constant(v1) && is_constant(v2) && is_constant(v3))
      return false;

    // Each variable must be canonical (leader)
    if (!v1.isCanonical()) {
      if (dlog != null)
        dlog.log(debug, "Ternary slice not created, var1 not lead");
      return (false);
    }
    if (!v2.isCanonical()) {
      if (dlog != null)
        dlog.log(debug, "Ternary slice not created, var2 not lead");
      return (false);
    }
    if (!v3.isCanonical()) {
      if (dlog != null)
        dlog.log(debug, "Ternary slice not created, var3 not lead");
      return (false);
    }

    // For now, each variable must also not be an array (ternary only)
    if (v1.rep_type.isArray()) {
      if (dlog != null)
        dlog.log(debug, "Ternary slice not created, var1 is an array");
      return (false);
    }
    if (v2.rep_type.isArray()) {
      if (dlog != null)
        dlog.log(debug, "Ternary slice not created, var2 is an array");
      return (false);
    }
    if (v3.rep_type.isArray()) {
      if (dlog != null)
        dlog.log(debug, "Ternary slice not created, var3 is an array");
      return (false);
    }

    // Vars must be compatible
    if (!v1.compatible(v2) || !v1.compatible(v3) || !v2.compatible(v3)) {
      if (dlog != null)
        dlog.log(debug, "Ternary slice not created, vars not compatible");
      return (false);
    }

    // For now, each variable must be integral or float.  We only need
    // to check the first variable since comparability will handle the
    // others
    if (!v1.file_rep_type.isIntegral() && !v1.file_rep_type.isFloat()) {
      if (dlog != null)
        dlog.log(
          debug,
          "Ternary slice not created, vars are neither " + "integral or float");
      return (false);
    }
    Assert.assertTrue(
      v2.file_rep_type.isIntegral() || v2.file_rep_type.isFloat());
    Assert.assertTrue(
      v3.file_rep_type.isIntegral() || v3.file_rep_type.isFloat());

    // Don't create a reflexive slice (all vars the same) if there are
    // only two vars in the equality set
    if ((v1 == v2) && (v2 == v3) && (v1.get_equalitySet_size() <= 2))
      return (false);

    // Don't create a partially reflexive slice (two vars the same) if there
    // is only one variable in its equality set
    if (false) {
      if ((v1 == v2) || (v1 == v3) && (v1.get_equalitySet_size() == 1))
        return (false);
      if ((v2 == v3) && (v2.get_equalitySet_size() == 1))
        return (false);
    }

    return (true);
  }

  /**
   * Determines whether the order of the variables in vis is a valid
   * permutation (ie, their varinfo_index's are ordered).  Null
   * elements are ignored (and an all-null list is ok)
   */
  public boolean vis_order_ok(VarInfo[] vis) {

    VarInfo prev = vis[0];
    for (int i = 1; i < vis.length; i++) {
      if ((prev != null) && (vis[i] != null)) {
        if (vis[i].varinfo_index < prev.varinfo_index)
          return (false);
      }
      if (vis[i] != null)
        prev = vis[i];
    }
    return (true);
  }

  /**
   * Returns whether or not the specified slice is made up of only
   * variables linked to those in the global ppt (ie, whether they are
   * globals).
   */
  public boolean is_slice_global(VarInfo[] vis) {
    if (vis.length == 1)
      return (is_slice_global(vis[0]));
    else if (vis.length == 2)
      return (is_slice_global(vis[0], vis[1]));
    else
      return (is_slice_global(vis[0], vis[1], vis[2]));
  }

  /**
   * Returns whether or not this slice is made up of only variables linked
   * to those in the global ppt (ie, whether they are globals).
   */
  public boolean is_slice_global(VarInfo vi) {

    return (vi.is_global());
  }

  /**
   * Returns whether or not this slice is made up of only variables linked
   * to those in the global ppt (ie, whether they are globals).  They must
   * also follow the same transform (global or orig)
   */
  public boolean is_slice_global(VarInfo vi1, VarInfo vi2) {

    if (vi1.is_orig_global() && vi2.is_orig_global())
      return (true);

    if (vi1.is_post_global() && vi2.is_post_global())
      return (true);

    return (false);
  }
  /**
   * Returns whether or not this slice is made up of only variables linked
   * to those in the global ppt (ie, whether they are globals).  They must
   * also follow the same transform (global or orig)
   */
  public boolean is_slice_global(VarInfo vi1, VarInfo vi2, VarInfo vi3) {

    if (vi1.is_orig_global() && vi2.is_orig_global() && vi3.is_orig_global())
      return (true);

    if (vi1.is_post_global() && vi2.is_post_global() && vi3.is_post_global())
      return (true);

    return (false);
  }

  /**
   * Return a slice that contains the given VarInfos (creating if
   * needed).  It is incumbent on the caller that the slice be either
   * filled with one or more invariants, or else removed from the
   * views collection.<p>
   *
   * When the arity of the slice is known, call one of the overloaded
   * definitions of get_or_instantiate_slice that takes (one or more)
   * VarInfo arguments; they are more efficient.
   *
   * @param vis array of VarInfo objects; is not used internally
   *      (so the same value can be passed in repeatedly).  Can be unsorted.
   **/
  public PptSlice get_or_instantiate_slice(VarInfo[] vis) {
    switch (vis.length) {
      case 1 :
        return get_or_instantiate_slice(vis[0]);
      case 2 :
        return get_or_instantiate_slice(vis[0], vis[1]);
      case 3 :
        return get_or_instantiate_slice(vis[0], vis[1], vis[2]);
      default :
        throw new IllegalArgumentException("bad length = " + vis.length);
    }
  }

  /**
   * Return a slice that contains the given VarInfos (creating if
   * needed).  It is incumbent on the caller that the slice be either
   * filled with one or more invariants, or else removed from the
   * views collection.
   **/
  public PptSlice get_or_instantiate_slice(VarInfo vi) {
    PptSlice result = findSlice(vi);
    if (result != null)
      return result;

    // We may do inference over static constants
    // Assert.assertTrue(! vi.isStaticConstant());
    result = new PptSlice1(this, vi);

    addSlice(result);
    return result;
  }

  /**
   * Return a slice that contains the given VarInfos (creating if
   * needed).  It is incumbent on the caller that the slice be either
   * filled with one or more invariants, or else removed from the
   * views collection.
   **/
  public PptSlice get_or_instantiate_slice(VarInfo v1, VarInfo v2) {
    VarInfo tmp;
    if (v1.varinfo_index > v2.varinfo_index) {
      tmp = v2;
      v2 = v1;
      v1 = tmp;
    }

    PptSlice result = findSlice(v1, v2);
    if (result != null)
      return result;

    // We may do inference over static constants
    // Assert.assertTrue(! v1.isStaticConstant());
    // Assert.assertTrue(! v2.isStaticConstant());
    result = new PptSlice2(this, v1, v2);

    addSlice(result);
    return result;
  }

  /**
   * Return a slice that contains the given VarInfos (creating if
   * needed).  It is incumbent on the caller that the slice be either
   * filled with one or more invariants, or else removed from the
   * views collection.
   **/
  public PptSlice get_or_instantiate_slice(
    VarInfo v1,
    VarInfo v2,
    VarInfo v3) {
    VarInfo tmp;
    if (v1.varinfo_index > v2.varinfo_index) {
      tmp = v2;
      v2 = v1;
      v1 = tmp;
    }
    if (v2.varinfo_index > v3.varinfo_index) {
      tmp = v3;
      v3 = v2;
      v2 = tmp;
    }
    if (v1.varinfo_index > v2.varinfo_index) {
      tmp = v2;
      v2 = v1;
      v1 = tmp;
    }

    PptSlice result = findSlice(v1, v2, v3);
    if (result != null)
      return result;

    // We may do inference over static constants
    // Assert.assertTrue(! v1.isStaticConstant());
    // Assert.assertTrue(! v2.isStaticConstant());
    // Assert.assertTrue(! v3.isStaticConstant());
    result = new PptSlice3(this, v1, v2, v3);

    addSlice(result);
    return result;
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Creating conditioned views
  ///

  // This static region can't appear in PptConditional, lest it never get
  // called.  PptConditional isn't instantiated unless it needs to be, but
  // it doesn't need to be unless we run this static region!

  static {
    if (!Daikon.dkconfig_disable_splitting) {
      // new MiscSplitters();

      SplitterList.put(".*", new Splitter[] { new ReturnTrueSplitter(), });
    }
  }

  public void addConditions(Splitter[] splits) {

    debugConditional.fine("Applying splits to " + name());

    if ((splits == null) || (splits.length == 0)) {
      debugConditional.fine("No splits for " + name());
      return;
    }

    // for (int i=0; i<splits.length; i++) {
    //   Assert.assertTrue(splits[i].instantiated() == false);
    // }

    // Create a Conditional ppt for each side of each splitter
    splitters = new ArrayList(splits.length);
    for (int ii = 0; ii < splits.length; ii++) {
      PptSplitter ppt_split = new PptSplitter(this, splits[ii]);
      if (!ppt_split.splitter_valid()) {
        debugConditional.fine(
          "Splitter ("
            + ppt_split.splitter.getClass()
            + ") not valid: "
            + ppt_split.ppts[0].name);
        continue;
      }
      splitters.add(ppt_split);
      if (debugConditional.isLoggable(Level.FINE))
        debugConditional.fine("Added PptSplitter: " + ppt_split);
    }

  }

  /**
   * Given conditional program points (and invariants detected over them),
   * create implications.  Configuration variable "pairwise_implications"
   * controls whether all or only the first two conditional program points
   * are considered.
   **/
  public void addImplications() {

    if (Daikon.dkconfig_disable_splitting)
      return;

    // Add implications from each splitter
    if (splitters != null) {
      for (int i = 0; i < splitters.size(); i++) {
        PptSplitter ppt_split = (PptSplitter) splitters.get(i);
        ppt_split.add_implications();
      }
    }

    // If this is a combined exit point with two individual exits, create
    // implications from the two exit points
    if (ppt_name.isCombinedExitPoint()) {
      List exit_points = new ArrayList();
      for (Iterator ii = children.iterator(); ii.hasNext();) {
        PptRelation rel = (PptRelation) ii.next();
        if (rel.getRelationType() == PptRelation.EXIT_EXITNN)
          exit_points.add(rel.child);
      }
      if (exit_points.size() == 2) {
        PptTopLevel ppt1 = (PptTopLevel) exit_points.get(0);
        PptTopLevel ppt2 = (PptTopLevel) exit_points.get(1);
        PptSplitter ppt_split = new PptSplitter(this, ppt2, ppt1);
        ppt_split.add_implications();
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Post processing after data trace files are read (but before printing)
  ///

  /**
   * Two things: a) convert Equality invariants into normal IntEqual
   * type for filtering, printing, etc. b) Pivot uninteresting
   * parameter VarInfos so that each equality set contains only the
   * interesting one.
   **/
  public void postProcessEquality() {
    if (debugEqualTo.isLoggable(Level.FINE)) {
      debugEqualTo.fine("PostProcessingEquality for: " + this.name());
    }
    if (num_samples() == 0)
      return;
    Assert.assertTrue(equality_view != null, "ppt = " + ppt_name);
    Invariants equalityInvs = equality_view.invs;

    // Pivot invariants to new equality leaders if needed, if old
    // leaders would prevent printing.
    for (Iterator i = equalityInvs.iterator(); i.hasNext();) {
      Equality inv = (Equality) i.next();
      inv.pivot();
    }

    // Now pivot the other invariants
    Collection slices = viewsAsCollection();
    List pivoted = new LinkedList();

    // PptSlice newSlice = slice.cloneAndInvs(leader, newLeader);

    // Pivot each pptSlice so that each of its VarInfos map back to
    // their leaders.
    if (debugEqualTo.isLoggable(Level.FINE)) {
      debugEqualTo.fine("  Doing cloneAllPivots: ");
    }
    for (Iterator iSlices = slices.iterator(); iSlices.hasNext();) {
      PptSlice slice = (PptSlice) iSlices.next();
      VarInfo[] newVis = new VarInfo[slice.arity()];
      boolean needPivoting = false;
      for (int i = 0; i < slice.arity(); i++) {
        if (slice.var_infos[i].canonicalRep() != slice.var_infos[i])
          needPivoting = true;
      }
      if (!needPivoting)
        continue;
      for (int i = 0; i < slice.arity(); i++) {
        newVis[i] = slice.var_infos[i].canonicalRep();
      }
      PptSlice newSlice = slice.cloneAndPivot(newVis);
      if (slice != newSlice) {
        pivoted.add(newSlice);
        iSlices.remove(); // Because the key is now wrong
      }
    }

    // Add in the removed slices
    for (Iterator iPivoted = pivoted.iterator(); iPivoted.hasNext();) {
      PptSlice oPivoted = (PptSlice) iPivoted.next();
      addSlice(oPivoted); // Make the key right again
      if (debugEqualTo.isLoggable(Level.FINE)) {
        debugEqualTo.fine("  Readded: " + oPivoted);
      }
    }

    // Add specific equality invariants for each member of the
    // equality set
    for (Iterator i = equalityInvs.iterator(); i.hasNext();) {
      Equality inv = (Equality) i.next();
      inv.postProcess();
    }

  }

  ///////////////////////////////////////////////////////////////////////////
  /// Locating implied (same) invariants via the simplify theorem-prover
  ///

  // Created upon first use, then saved
  private static LemmaStack proverStack = null;

  /**
   * Interface used by mark_implied_via_simplify to determine what
   * invariants should be considered during the logical redundancy
   * tests.
   **/
  public static interface SimplifyInclusionTester {
    public boolean include(Invariant inv);
  }

  /**
   * Use the Simplify theorem prover to flag invariants that are
   * logically implied by others.  Considers only invariants that
   * pass isWorthPrinting.
   **/
  public void mark_implied_via_simplify(PptMap all_ppts) {
    try {
      if (proverStack == null)
        proverStack = new LemmaStack();
      markImpliedViaSimplify_int(all_ppts, new SimplifyInclusionTester() {
        public boolean include(Invariant inv) {
          return InvariantFilters.isWorthPrintingFilter().shouldKeep(inv)
            == null;
        }
      });
    } catch (SimplifyError e) {
      proverStack = null;
    }
  }

  /**
   * Returns true if there was a problem with Simplify formatting (such as
   * the invariant not having a Simplify representation).
   **/
  private static boolean format_simplify_problem(String s) {
    return (
      (s.indexOf("Simplify") >= 0)
        || (s.indexOf("format(OutputFormat:Simplify)") >= 0)
        || (s.indexOf("format_simplify") >= 0));
  }

  /**
   * Use the Simplify theorem prover to flag invariants that are
   * logically implied by others.  Uses the provided test interface to
   * determine if an invariant is within the domain of inspection.
   **/
  private void markImpliedViaSimplify_int(
    PptMap all_ppts,
    SimplifyInclusionTester test)
    throws SimplifyError {
    SessionManager.debugln("Simplify checking " + ppt_name);

    // Create the list of invariants from this ppt which are
    // expressible in Simplify
    Invariant[] invs;
    {
      // Replace parwise equality with an equivalence set
      Vector all_noeq = invariants_vector();
      Collections.sort(all_noeq, icfp);
      List all = InvariantFilters.addEqualityInvariants(all_noeq);
      Collections.sort(all, icfp);
      Vector printing = new Vector(); // [Invariant]
      for (Iterator _invs = all.iterator(); _invs.hasNext();) {
        Invariant inv = (Invariant) _invs.next();
        if (test.include(inv)) { // think: inv.isWorthPrinting()
          String fmt = inv.format_using(OutputFormat.SIMPLIFY);
          if (!format_simplify_problem(fmt)) {
            // If format_simplify is not defined for this invariant, don't
            // confuse Simplify with the error message
            printing.add(inv);
          }
        }
      }
      invs = (Invariant[]) printing.toArray(new Invariant[printing.size()]);
    }

    // For efficiency, bail if we don't have any invariants to mark as implied
    if (invs.length == 0) {
      return;
    }

    // Come up with a "desirability" ordering of the printing and
    // expressible invariants, so that we can remove the least
    // desirable first.  For now just use the ICFP.
    Arrays.sort(invs, icfp);

    // Debugging
    if (Global.debugSimplify.isLoggable(Level.FINE)) {
      Global.debugSimplify.fine("Sorted invs:");
      for (int i = 0; i < invs.length; i++) {
        Global.debugSimplify.fine("    " + invs[i].format());
      }
      for (int i = 0; i < invs.length - 1; i++) {
        int cmp = icfp.compare(invs[i], invs[i + 1]);
        Global.debugSimplify.fine("cmp(" + i + "," + (i + 1) + ") = " + cmp);
        int rev_cmp = icfp.compare(invs[i + 1], invs[i]);
        Global.debugSimplify.fine(
          "cmp(" + (i + 1) + "," + i + ") = " + rev_cmp);
        Assert.assertTrue(rev_cmp >= 0);
      }
    }

    // The below two paragraphs of code (whose end result is to
    // compute "background") should be changed to use the VarInfo
    // partial ordering to determine background invariants, instead of
    // the (now deprecated) controlling_ppts relationship.

    // Form the closure of the controllers; each element is a Ppt
    Set closure = new LinkedHashSet();
    {
      Set working = new LinkedHashSet();
      while (!working.isEmpty()) {
        PptTopLevel ppt = (PptTopLevel) working.iterator().next();
        working.remove(ppt);
        if (!closure.contains(ppt)) {
          closure.add(ppt);
        }
      }
    }

    // Create the conjunction of the closures' invariants to form a
    // background environment for the prover.  Ignore implications,
    // since in the current scheme, implications came from controlled
    // program points, and we don't necessarily want to lose the
    // unconditional version of the invariant at the conditional ppt.
    for (Iterator ppts = closure.iterator(); ppts.hasNext();) {
      PptTopLevel ppt = (PptTopLevel) ppts.next();
      Vector invs_vec = ppt.invariants_vector();
      Collections.sort(invs_vec, icfp);
      Iterator _invs =
        InvariantFilters.addEqualityInvariants(invs_vec).iterator();
      while (_invs.hasNext()) {
        Invariant inv = (Invariant) _invs.next();
        if (inv instanceof Implication) {
          continue;
        }
        if (!test.include(inv)) { // think: !inv.isWorthPrinting()
          continue;
        }
        String fmt = inv.format_using(OutputFormat.SIMPLIFY);
        if (format_simplify_problem(fmt)) {
          // If format_simplify is not defined for this invariant, don't
          // confuse Simplify with the error message
          continue;
        }
        // We could also consider testing if the controlling invariant
        // was removed by Simplify, but what would the point be?  Also,
        // these "intermediate goals" might help out Simplify.
        proverStack.pushLemma(new InvariantLemma(inv));

        // If this is the :::OBJECT ppt, also restate all of them in
        // orig terms, since the conditions also held upon entry.
        if (ppt.ppt_name.isObjectInstanceSynthetic())
          proverStack.pushLemma(InvariantLemma.makeLemmaAddOrig(inv));
      }
    }

    // FIXME XXXXX:  Commented out by MDE, 6/26/2002, due to merging problems.
    // Should this be deleted?  Need to check CVS logs and/or think about this.
    /*
    if (ppt_name.isEnterPoint() && controlling_ppts.size() == 1) {
      // Guess the OBJECT ppt; usually right
      PptTopLevel OBJ = (PptTopLevel) controlling_ppts.iterator().next();
      if (OBJ.ppt_name.isObjectInstanceSynthetic()) {
        // Find variables here of the same type as us
        String clsname = ppt_name.getFullClassName();
      }
    }
    
    // Use type information to restate any OBJECT invariants over
    // variable expressions such as arguments or fields whose types
    // are instrumeted.
    for (int i=0; i < var_infos.length; i++) {
      VarInfo vi = var_infos[i];
      ProglangType progtype = vi.type;
    
      // Always skip "this" and "orig(this)" as necessary special cases.
      if (VarInfoName.THIS.equals(vi.name) ||
          VarInfoName.ORIG_THIS.equals(vi.name)) {
        continue;
      }
    
      // For now, we don't handle sequences.  We could use a GLB type
      // and state a forall, but it doesn't seem worth the work yet.
      if (progtype.isPseudoArray()) {
        continue;
      }
    
      // Locate the OBJECT ppt
      PptName obj_name = new PptName(vi.type.base(), null,
                                     FileIO.object_suffix);
      PptTopLevel obj_ppt = all_ppts.get(obj_name);
      if (obj_ppt == null) {
        Global.debugSimplify.fine
          (ppt_name + ": no type-based invariants found for "
           + vi.name + " (" + obj_name + ")");
        continue;
      }
    
      Global.debugSimplify.fine
          (ppt_name + ": using type-based invariants for "
           + vi.name + " (" + obj_name + ")");
    
      // State the object invariant on the incoming argument
      Vector invs2 = obj_ppt.invariants_vector();
      Collections.sort(invs2, icfp);
      Iterator _invs
        = InvariantFilters.addEqualityInvariants(invs2).iterator();
      while (_invs.hasNext()) {
        Invariant inv = (Invariant) _invs.next();
        if (!test.include(inv)) { // think: !inv.isWorthPrinting()
          continue;
        }
        String fmt = inv.format_using(OutputFormat.SIMPLIFY);
        if (format_simplify_problem(fmt)) {
          continue;
        }
        Lemma replaced = InvariantLemma.makeLemmaReplaceThis(inv, vi.name);
        proverStack.pushLemma(replaced);
      }
    }
    */

    if (proverStack.checkForContradiction() == 'T') {
      if (LemmaStack.dkconfig_remove_contradictions) {
        System.err.println(
          "Warning: "
            + ppt_name
            + " background is contradictory, "
            + "removing some parts");
        proverStack.removeContradiction();
      } else {
        System.err.println(
          "Warning: " + ppt_name + " background is contradictory, giving up");
        return;
      }
    }

    int backgroundMark = proverStack.markLevel();

    InvariantLemma[] lemmas = new InvariantLemma[invs.length];
    for (int i = 0; i < invs.length; i++)
      lemmas[i] = new InvariantLemma(invs[i]);
    boolean[] present = new boolean[lemmas.length];
    Arrays.fill(present, 0, present.length, true);
    for (int checking = invs.length - 1; checking >= 0; checking--) {
      Invariant inv = invs[checking];
      StringBuffer bg = new StringBuffer("(AND ");
      for (int i = 0; i < present.length; i++) {
        if (present[i] && (i != checking)) {
          bg.append(" ");
          // format_using(OutputFormat.SIMPLIFY) is guaranteed to return
          // a sensible result xfor invariants in invs[].
          bg.append(invs[i].format_using(OutputFormat.SIMPLIFY));
        }
      }
      bg.append(")");

      // Debugging
      if (Global.debugSimplify.isLoggable(Level.FINE)) {
        SessionManager.debugln("Background:");
        for (int i = 0; i < present.length; i++) {
          if (present[i] && (i != checking)) {
            SessionManager.debugln("    " + invs[i].format());
          }
        }
      }
    }

    for (int i = 0; i < invs.length; i++)
      proverStack.pushLemma(lemmas[i]);

    // If the background is necessarily false, we are in big trouble
    if (proverStack.checkForContradiction() == 'T') {
      // Uncomment to punt on contradictions
      if (!LemmaStack.dkconfig_remove_contradictions) {
        System.err.println(
          "Warning: " + ppt_name + " invariants are contradictory, giving up");
        if (LemmaStack.dkconfig_print_contradictions) {
          LemmaStack.printLemmas(
            System.err,
            proverStack.minimizeContradiction());
        }
      }
      System.err.println(
        "Warning: " + ppt_name + " invariants are contradictory, axing some");
      Map demerits = new TreeMap();
      int worstWheel = 0;
      do {
        // But try to recover anyway
        Vector problems = proverStack.minimizeContradiction();
        if (LemmaStack.dkconfig_print_contradictions) {
          System.err.println("Minimal set:");
          LemmaStack.printLemmas(
            System.err,
            proverStack.minimizeContradiction());
          System.err.println();
        }
        if (problems.size() == 0) {
          System.err.println("Warning: removal failed, punting");
          return;
        }
        for (int j = 0; j < problems.size(); j++) {
          Lemma problem = (Lemma) problems.elementAt(j);
          if (demerits.containsKey(problem))
            demerits.put(
              problem,
              new Integer(((Integer) demerits.get(problem)).intValue() + 1));
          else
            demerits.put(problem, new Integer(1));
        }
        int max_demerits = -1;
        Vector worst = new Vector();
        Iterator it = demerits.entrySet().iterator();
        while (it.hasNext()) {
          Map.Entry ent = (Map.Entry) it.next();
          int value = ((Integer) ent.getValue()).intValue();
          if (value == max_demerits) {
            worst.add(ent.getKey());
          } else if (value > max_demerits) {
            max_demerits = value;
            worst = new Vector();
            worst.add(ent.getKey());
          }
        }
        int offsetFromEnd = worstWheel % worst.size();
        worstWheel = (3 * worstWheel + 1) % 10000019;
        int index = worst.size() - 1 - offsetFromEnd;
        Lemma bad = (Lemma) worst.elementAt(index);
        demerits.remove(bad);
        proverStack.popToMark(backgroundMark);
        boolean isInvariant = false;
        for (int i = 0; i < lemmas.length; i++) {
          if (lemmas[i] == bad) {
            present[i] = false;
            isInvariant = true;
          } else if (present[i]) {
            proverStack.pushLemma(lemmas[i]);
          }
        }
        if (!isInvariant)
          proverStack.removeLemma(bad);
        if (LemmaStack.dkconfig_print_contradictions) {
          System.err.println("Removing " + bad.summarize());
        } else if (Daikon.no_text_output && Daikon.show_progress) {
          System.err.print("x");
        }
      }
      while (proverStack.checkForContradiction() == 'T');
    }

    proverStack.popToMark(backgroundMark);

    flagRedundantRecursive(lemmas, present, 0, lemmas.length - 1);

    proverStack.clear();
  }

  /** Go though an array of invariants, marking those that can be
   * proved as consequences of others as redundant. */
  private void flagRedundantRecursive(
    InvariantLemma[] lemmas,
    boolean[] present,
    int start,
    int end)
    throws SimplifyError {
    Assert.assertTrue(start <= end);
    if (start == end) {
      // Base case: check a single invariant
      int checking = start;
      if (proverStack.checkLemma(lemmas[checking]) == 'T') {
        //         System.err.println("-------------------------");
        //         System.err.println(lemmas[checking].summarize() +
        //                            " is redundant because of");
        //         LemmaStack.printLemmas(System.err,
        //                                proverStack.minimizeProof(lemmas[checking]));
        flagRedundant(lemmas[checking].invariant);
        present[checking] = false;
      }
      SessionManager.debugln(
        (present[checking] ? "UNIQUE" : "REDUNDANT")
          + " "
          + lemmas[checking].summarize());
    } else {
      // Recursive case: divide and conquer
      int first_half_end = (start + end) / 2;
      int second_half_start = first_half_end + 1;
      int mark = proverStack.markLevel();
      // First, assume the first half and check the second half
      for (int i = start; i <= first_half_end; i++) {
        if (present[i])
          proverStack.pushLemma(lemmas[i]);
      }
      flagRedundantRecursive(lemmas, present, second_half_start, end);
      proverStack.popToMark(mark);
      // Now, assume what's left of the second half, and check the
      // first half.
      for (int i = second_half_start; i <= end; i++) {
        if (present[i])
          proverStack.pushLemma(lemmas[i]);
      }
      flagRedundantRecursive(lemmas, present, start, first_half_end);
      proverStack.popToMark(mark);
    }
  }

  /** Mark an invariant as redundant. */
  private void flagRedundant(Invariant inv) {
    if (inv instanceof Equality) {
      // ick ick ick
      // Equality is not represented with a permanent invariant
      // object, so store the canonical variable instead.
      redundant_invs.add(((Equality) inv).leader());
    } else {
      redundant_invs.add(inv);
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Parameter VarInfo processing
  ///

  /**
   * Cached VarInfoNames that are parameter variables.
   **/
  private Set paramVars = null;

  /**
   * Returns variables in this Ppt that are parameters.
   **/
  public Set getParamVars() {
    if (paramVars != null)
      return paramVars;

    paramVars = new LinkedHashSet();
    for (int i = 0; i < var_infos.length; i++) {
      VarInfo var = var_infos[i];
      if (var.aux.getFlag(VarInfoAux.IS_PARAM) && !var.isPrestate()) {
        paramVars.add(var.name);
      }
    } // We should cache paramedVars in PptToplevel
    return paramVars;
  }

  ///////////////////////////////////////////////////////////////////////////
  /// Printing invariants
  ///

  // This is a fairly inefficient method, as it does a lot of copying.
  // As of 1/9/2000, this is only used in print_invariants.
  /**
   * Return a List of all the invariants for the program point.
   * Also consider using views_iterator() instead.  You can't
   * modify the result of this.
   **/
  // Used to be known as invariants_vector, but changed to return a
  // List.
  public List getInvariants() {
    List result = new ArrayList();
    for (Iterator itor = new ViewsIteratorIterator(this); itor.hasNext();) {
      for (Iterator itor2 = (Iterator) itor.next(); itor2.hasNext();) {
        result.add(itor2.next());
      }
    }
    // Old implementation:  was slightly more efficient, but separate code
    // permitted drift between it an ViewsIteratorIterator.
    // for (Iterator views_itor = views.iterator(); views_itor.hasNext(); ) {
    //   PptSlice slice = (PptSlice) views_itor.next();
    //   result.addAll(slice.invs);
    // }
    // // System.out.println(implication_view.invs.size() + " implication invs for " + name() + " at " + implication_view.name);
    // result.addAll(implication_view.invs);
    return Collections.unmodifiableList(result);
  }

  // restored to ease merge between V2 and V3.  Its unclear why the above
  // was changed in any event...
  public Vector invariants_vector() {
    return new Vector(getInvariants());
  }

  /**
   * @return the number of views
   **/
  public int views_size() {
    return viewsAsCollection().size();
  }

  /**
   * For some clients, this method may be more efficient than getInvariants.
   **/
  public Iterator views_iterator() {
    // assertion only true when guarding invariants
    // Assert.assertTrue(views.contains(joiner_view));
    return viewsAsCollection().iterator();
  }

  public Iterator invariants_iterator() {
    return new UtilMDE.MergedIterator(views_iterator_iterator());
  }

  private Iterator views_iterator_iterator() {
    return new ViewsIteratorIterator(this);
  }

  /** An iterator whose elements are themselves iterators that return invariants. **/
  public static final class ViewsIteratorIterator implements Iterator {
    Iterator vitor;
    Iterator implication_iterator;
    public ViewsIteratorIterator(PptTopLevel ppt) {
      vitor = ppt.views_iterator();
      implication_iterator = ppt.joiner_view.invs.iterator();
    }
    public boolean hasNext() {
      return (vitor.hasNext() || (implication_iterator != null));
    }
    public Object next() {
      if (vitor.hasNext())
        return ((PptSlice) vitor.next()).invs.iterator();
      else {
        Iterator tmp = implication_iterator;
        implication_iterator = null;
        return tmp;
      }
    }
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Simplify the names of variables before printing them.  For
   * example, "orig(a[post(i)])" might change into "orig(a[i+1])".  We
   * might want to switch off this behavior, depending on various
   * heuristics.  We'll have to try it and see which output we like
   * best.  In any case, we have to do this for ESC output, since ESC
   * doesn't have anything like post().
   **/
  public void simplify_variable_names() {
    Iterator iter = Arrays.asList(var_infos).iterator();
    while (iter.hasNext()) {
      VarInfo vi = (VarInfo) iter.next();
      vi.simplify_expression();
    }
  }

  public static final Comparator icfp =
    new Invariant.InvariantComparatorForPrinting();

  static Comparator arityVarnameComparator =
    new PptSlice.ArityVarnameComparator();

  //////////////////////////////////////////////////////////////////////////////
  ///// Invariant guarding

  // This function guards all of the invariants in a PptTopLevel
  public void guardInvariants() {
    // To avoid concurrent modification exceptions using arrays
    Object[] viewArray = viewsAsCollection().toArray();
    for (int i = 0; i < viewArray.length; i++) {
      PptSlice currentView = (PptSlice) viewArray[i];
      currentView.guardInvariants();
    }

    // Commented this code out because conditional views are not slices.
    // It is not clear what this is trying to accomplish
    //     Object viewCondArray[] = views_cond.toArray();
    //     for (int i=0; i < viewCondArray.length; i++) {
    //       PptSlice currentCondView = (PptSlice)viewCondArray[i];
    //       currentCondView.guardInvariants();
    //     }
    // This is a version changed to use the new conditional ppt iterator.
    // But the elements are still not slices!
    //     for (Iterator i = cond_iterator(); i.hasNext(); ) {
    //       PptSlice currentCondView = (PptSlice) i.next();
    //       currentCondView.guardInvariants();
    //     }

    // System.out.println("Ppt name: " + name());
    // System.out.println("Number of invs in joiner_view: " + joiner_view.invs.size());
  }

  public void processOmissions(boolean[] omitTypes) {
    // Avoid concurrent modification exceptions using arrays
    Object[] viewArray = viewsAsCollection().toArray();
    for (int i = 0; i < viewArray.length; i++) {
      PptSlice currentView = (PptSlice) viewArray[i];
      currentView.processOmissions(omitTypes);
    }
    for (Iterator i = cond_iterator(); i.hasNext();) {
      PptConditional currentCondView = (PptConditional) i.next();
      currentCondView.processOmissions(omitTypes);
    }
  }

  /**
   * Check the rep invariants of this.  Throw an Error if not okay.
   **/
  public void repCheck() {
    // Check that the hashing of 'views' is working correctly. This
    // should really be beneath the abstraction layer of the hash
    // table, but it isn't because Java can't enforce the immutability
    // of the keys. In particular, we got into trouble in the past
    // when the keys had pointers to VarInfos which themselves
    // indirectly pointed back to us. If the serializer found the
    // VarInfo before it found us, the VarInfo would be in-progress at
    // the time the HashMap was serialized. At the point when When the
    // PptTopLevel was unserialized, the VarInfo pointers in the keys
    // would be null, causing them to have a different hashCode than
    // they should. When the VarInfo was fully unserialized, the key's
    // hashCode then changed to the correct one, messing up the
    // indexing in a hard-to-debug way. -SMcC
    Iterator view_keys_it = views.keySet().iterator();
    while (view_keys_it.hasNext()) {
      List this_key = (List) view_keys_it.next();
      Assert.assertTrue(views.containsKey(this_key));
    }
    // We could check a lot more than just that slices are okay.  For
    // example, we could ensure that flow graph is correct.
    for (Iterator i = viewsAsCollection().iterator(); i.hasNext();) {
      PptSlice slice = (PptSlice) i.next();
      slice.repCheck();
    }
    if (equality_view != null)
      equality_view.repCheck();
    if (dataflow_ppts != null) {
      Assert.assertTrue(dataflow_ppts[dataflow_ppts.length - 1] == this);
    }
  }

  /**
   * Debug method to display all slices.
   **/
  public String debugSlices() {
    StringBuffer result = new StringBuffer();
    result.append("Slices for: " + this.ppt_name);
    for (Iterator i = viewsAsCollection().iterator(); i.hasNext();) {
      PptSlice slice = (PptSlice) i.next();
      result.append("\n" + slice.toString());
    }
    return result.toString();
  }

  /**
   * Debug method to print children (in the partial order) recursively.
   */
  public void debug_print_tree(Logger l, int indent, PptRelation parent_rel) {

    // Calculate the indentation
    String indent_str = "";
    for (int i = 0; i < indent; i++)
      indent_str += "--  ";

    // Get the type of the parent relation
    String rel_type = "";
    if (parent_rel != null)
      rel_type = parent_rel.getRelationType();

    // Calculate the variable relationships
    String var_rel = "[]";
    if (parent_rel != null)
      var_rel = "[" + parent_rel.parent_to_child_var_string() + "]";

    // Put out this item
    l.fine(indent_str + ppt_name + ": " + rel_type + ": " + var_rel);

    // Put out children if this is the primary relationship.  Limiting
    // this to primary relations simplifies the tree for viewing while
    // not leaving anything out.
    if ((parent_rel == null)
      || parent_rel.is_primary()
      || (Daikon.ppt_regexp != null)) {
      for (Iterator i = children.iterator(); i.hasNext();)
         ((PptRelation) (i.next())).debug_print_tree(l, indent + 1);
    }
  }

  /**
   * Returns a string version of all of the equality sets for this ppt.
   * The string is of the form [a,b], [c,d] where a,b and c,d are
   * each in an equality set.  Should be used only for debugging.
   */
  public String equality_sets_txt() {

    if (equality_view == null)
      return ("null");

    String out = "";
    for (int i = 0; i < equality_view.invs.size(); i++) {
      Equality e = (Equality) equality_view.invs.get(i);
      Set vars = e.getVars();
      String set_str = "";
      for (Iterator j = vars.iterator(); j.hasNext();) {
        VarInfo v = (VarInfo) j.next();
        if (set_str != "") // interned
          set_str += ",";
        set_str += v.name.name();
        if (v.missingOutOfBounds())
          set_str += "{MOB}";
      }
      if (out != "") // interned
        out += ", ";
      out += "[" + set_str + "]";
    }

    return (out);
  }

  /**
   * Returns whether or not the specified variable in this ppt has any
   * parents.
   */
  public boolean has_parent(VarInfo v) {

    for (Iterator i = parents.iterator(); i.hasNext();) {
      PptRelation rel = (PptRelation) i.next();
      if (rel.parentVar(v) != null)
        return (true);
    }

    return (false);
  }

  /**
   * Recursively merge invariants from children to create an invariant
   * list at this ppt.
   *
   * First, equality sets are created for this ppt.  These are the
   * intersection of the equality sets from each child.  Then create
   * unary, binary, and ternary slices for each combination of equality
   * sets and build the invariants for each slice.
   */

  public void mergeInvs() {

    // If we don't have any children, there is nothing to do.
    if (children.size() == 0)
      return;

    // If this has already been done (because this ppt has multiple parents)
    // there is nothing to do.
    if (invariants_merged)
      return;

    // First do this for any children.
    for (Iterator i = children.iterator(); i.hasNext();) {
      PptRelation rel = (PptRelation) i.next();
      rel.child.mergeInvs();
    }

    //Fmt.pf ("Merging ppt " + name + " with " + children.size() + " children");
    if (debugMerge.isLoggable(Level.FINE))
      debugMerge.fine("Processing ppt " + name());

    Stopwatch watch = null;
    if (debugTimeMerge.isLoggable(Level.FINE)) {
      watch = new Stopwatch();
      if (children.size() == 1)
        debugTimeMerge.fine(
          "Timing merge of 1 child ("
            + ((PptRelation) children.get(0)).child.name
            + " under ppt "
            + name);
      else
        debugTimeMerge.fine(
          "Timing merge of " + children.size() + " children under ppt " + name);
    }

    // Number of samples here is the sum of all of the child samples, presuming
    // there are some variable relationships with the child (note that
    // some ppt relationships such as constructor ENTER ppts to their
    // object ppts do not have any variable relationships)
    for (int i = 0; i < children.size(); i++) {
      PptRelation rel = (PptRelation) children.get(i);
      if (rel.size() > 0)
        values_num_samples += rel.child.values_num_samples;
    }

    // Merge any always missing variables from the children
    if (Daikon.dkconfig_use_dynamic_constant_optimization) {
      Assert.assertTrue(constants == null);
      constants = new DynamicConstants(this);
      constants.merge();
    }

    // Merge the ModBitTracker.
    // We'll reuse one dummy ValueTuple throughout, side-effecting its mods
    // array.
    int num_tracevars = mbtracker.num_vars();
    // warning: shadows field of same name
    Object[] vals = new Object[num_tracevars];
    int[] mods = new int[num_tracevars];
    ValueTuple vt = ValueTuple.makeUninterned(vals, mods);
    for (int childno = 0; childno < children.size(); childno++) {
      PptRelation rel = (PptRelation) children.get(childno);
      ModBitTracker child_mbtracker = rel.child.mbtracker;
      int child_mbsize = child_mbtracker.num_samples();
      // System.out.println("mergeInvs child #" + childno + "=" + rel.child.name() + " has size " + child_mbsize + " for " + name());
      for (int sampno = 0; sampno < child_mbsize; sampno++) {
        Arrays.fill(mods, ValueTuple.MISSING_FLOW);
        for (int j = 0; j < var_infos.length; j++) {
          VarInfo parent_vi = var_infos[j];
          VarInfo child_vi = rel.childVar(parent_vi);
          if ((child_vi != null) && (child_vi.value_index != -1)) {
            if (child_mbtracker.get(child_vi.value_index, sampno)) {
              mods[parent_vi.value_index] = ValueTuple.MODIFIED;
            }
          }
        }
        mbtracker.add(vt, 1);
      }
    }

    // Merge the ValueSets.
    for (int childno = 0; childno < children.size(); childno++) {
      PptRelation rel = (PptRelation) children.get(childno);

      for (int j = 0; j < var_infos.length; j++) {
        VarInfo parent_vi = var_infos[j];
        VarInfo child_vi = rel.childVar(parent_vi);
        if (child_vi != null) {
          Assert.assertTrue(parent_vi.ppt == this);
          if (parent_vi.value_index == -1) {
            continue;
          }
          ValueSet parent_vs = value_sets[parent_vi.value_index];
          ValueSet child_vs = rel.child.value_sets[child_vi.value_index];
          parent_vs.add(child_vs);
        }
      }
    }

    // Merge information stored in the VarInfo objects themselves.
    // Currently just the "canBeMissing" field, which is needed by
    // guarding, and the flag that marks missing-out-of-bounds
    // derived variables
    for (int i = 0; i < children.size(); i++) {
      PptRelation rel = (PptRelation) children.get(i);
      // This approach doesn't work correctly for the OBJECT_USER
      // relation case, because obj.field could be missing in a user PPT
      // when obj is null, but shouldn't be missing in the OBJECT PPT,
      // since "this" is always present for object invariants.
      // For the moment, just punt on this case, to match the previous
      // behavior.
      if (rel.getRelationType() == PptRelation.OBJECT_USER)
        continue;
      for (int j = 0; j < var_infos.length; j++) {
        VarInfo parent_vi = var_infos[j];
        VarInfo child_vi = rel.childVar(parent_vi);
        if (child_vi != null) {
          parent_vi.canBeMissing |= child_vi.canBeMissing;
          if (parent_vi.derived != null && child_vi.derived != null)
            parent_vi.derived.missing_array_bounds
              |= child_vi.derived.missing_array_bounds;
        }
      }
    }

    // Create the (empty) equality view for this ppt
    Assert.assertTrue(equality_view == null);
    equality_view = new PptSliceEquality(this);

    // Get all of the binary relationships from the first child's
    // equality sets.
    Map emap = null;
    int first_child = 0;
    for (first_child = 0; first_child < children.size(); first_child++) {
      PptRelation c1 = (PptRelation) children.get(first_child);
      debugMerge.fine(
        "looking at " + c1.child.name() + " " + c1.child.num_samples());
      if (c1.child.num_samples() > 0) {
        emap = c1.get_child_equalities_as_parent();
        debugMerge.fine("child " + c1.child.name() + " equality = " + emap);
        break;
      }
    }
    if (emap == null) {
      equality_view.instantiate_invariants();
      invariants_merged = true;
      return;
    }

    // Loop through the remaining children, intersecting the equal
    // variables and incrementing the sample count as we go
    for (int i = first_child + 1; i < children.size(); i++) {
      PptRelation rel = (PptRelation) children.get(i);
      if (rel.child.num_samples() == 0)
        continue;
      Map eq_new = rel.get_child_equalities_as_parent();
      for (Iterator j = emap.keySet().iterator(); j.hasNext();) {
        VarInfo.Pair curpair = (VarInfo.Pair) j.next();
        VarInfo.Pair newpair = (VarInfo.Pair) eq_new.get(curpair);
        if (newpair == null)
          j.remove();
        else
          curpair.samples += newpair.samples;
      }
    }
    if (debugMerge.isLoggable(Level.FINE)) {
      debugMerge.fine("Found equality pairs ");
      for (Iterator i = emap.keySet().iterator(); i.hasNext();)
        debugMerge.fine("-- " + (VarInfo.Pair) i.next());
    }

    // Build actual equality sets that match the pairs we found
    equality_view.instantiate_from_pairs(emap.keySet());
    if (debugMerge.isLoggable(Level.FINE)) {
      debugMerge.fine("Built equality sets ");
      for (int i = 0; i < equality_view.invs.size(); i++) {
        Equality e = (Equality) equality_view.invs.get(i);
        debugMerge.fine("-- " + e.shortString());
      }
    }

    if (debugTimeMerge.isLoggable(Level.FINE))
      debugTimeMerge.fine("    equality sets etc = " + watch.stop_start());

    // Merge the invariants
    if (children.size() == 1)
      merge_invs_one_child();
    else
      merge_invs_multiple_children();

    if (debugTimeMerge.isLoggable(Level.FINE))
      debugTimeMerge.fine("    merge invariants = " + watch.stop_start());

    // Merge the conditionals
    merge_conditionals();
    if (debugTimeMerge.isLoggable(Level.FINE))
      debugTimeMerge.fine("    conditionals = " + watch.stop_start());

    // Mark this ppt as merged, so we don't process it multiple times
    invariants_merged = true;

    // Remove any child invariants that now exist here
    if (dkconfig_remove_merged_invs) {
      for (Iterator i = children.iterator(); i.hasNext();) {
        PptRelation rel = (PptRelation) i.next();
        rel.child.remove_child_invs(rel);
      }
    }
    if (debugTimeMerge.isLoggable(Level.FINE))
      debugTimeMerge.fine("    removing child invs = " + watch.stop_start());

    // Remove the relations since we don't need it anymore
    if (dkconfig_remove_merged_invs) {
      for (Iterator i = children.iterator(); i.hasNext();) {
        PptRelation rel = (PptRelation) i.next();
        rel.child.parents.remove(rel);
      }
      children = new ArrayList(0);
    }
  }

  /**
   * Merges the invariants from multiple children.  NI suppression is handled
   * by first creating all of the suppressed invariants in each of the
   * children, performing the merge, and then removing them.
   */
  public void merge_invs_multiple_children() {

    // Fmt.pf ("merging multiple children of " + name);

    // There shouldn't be any slices when we start
    Assert.assertTrue(views.size() == 0);

    // Create an array of leaders to build slices over
    List non_missing_leaders = new ArrayList(equality_view.invs.size());
    for (int i = 0; i < equality_view.invs.size(); i++) {
      VarInfo l = ((Equality) equality_view.invs.get(i)).leader();
      if (l.missingOutOfBounds())
        continue;
      non_missing_leaders.add(l);
    }
    VarInfo[] leaders = new VarInfo[non_missing_leaders.size()];
    leaders = (VarInfo[]) non_missing_leaders.toArray(leaders);

    // Create any invariants in the children which are NI-suppressed and
    // remember the list for each child.  The same ppt can be a child
    // more than once (with different variable relations), but we only
    // need to created the suppressed invariants once.
    Map /*ppt->List<Invariant>*/
    suppressed_invs = new LinkedHashMap();
    for (int i = 0; i < children.size(); i++) {
      PptRelation rel = (PptRelation) children.get(i);
      PptTopLevel child = rel.child;
      if (child.num_samples() == 0)
        continue;
      if (suppressed_invs.get(child) != null)
        continue;
      suppressed_invs.put(child, NIS.create_suppressed_invs(child));
    }

    // Create unary views and related invariants
    List unary_slices = new ArrayList();
    for (int i = 0; i < leaders.length; i++) {
      PptSlice1 slice1 = new PptSlice1(this, leaders[i]);
      slice1.merge_invariants();
      unary_slices.add(slice1);
    }
    addSlices(unary_slices);
    if (debugMerge.isLoggable(Level.FINE))
      debug_print_slice_info(debugMerge, "unary", unary_slices);

    // Create binary views and related invariants
    List binary_slices = new ArrayList();
    for (int i = 0; i < leaders.length; i++) {
      for (int j = i; j < leaders.length; j++) {
        if (!is_slice_ok(leaders[i], leaders[j]))
          continue;
        PptSlice2 slice2 = new PptSlice2(this, leaders[i], leaders[j]);
        slice2.merge_invariants();
        if (slice2.invs.size() > 0)
          binary_slices.add(slice2);
      }
    }
    addSlices(binary_slices);
    if (debugMerge.isLoggable(Level.FINE))
      debug_print_slice_info(debugMerge, "binary", binary_slices);

    // Create ternary views and related invariants.  Since there
    // are no ternary array invariants, those slices don't need to
    // be created.
    List ternary_slices = new ArrayList();
    for (int i = 0; i < leaders.length; i++) {
      if (leaders[i].rep_type.isArray())
        continue;
      for (int j = i; j < leaders.length; j++) {
        if (leaders[j].rep_type.isArray())
          continue;
        if (!leaders[i].compatible(leaders[j]))
          continue;
        for (int k = j; k < leaders.length; k++) {
          if (!is_slice_ok(leaders[i], leaders[j], leaders[k]))
            continue;
          PptSlice3 slice3 =
            new PptSlice3(this, leaders[i], leaders[j], leaders[k]);
          // Fmt.pf ("Considering slice " + slice3);

          slice3.merge_invariants();

          // Merge any invariants that are NI-suppressed
          if (false) {
            PptSlice3 ni_slice3 =
              new PptSlice3(this, leaders[i], leaders[j], leaders[k]);
            ni_slice3.ni_merge_invariants();
            for (Iterator l = ni_slice3.invs.iterator(); l.hasNext();) {
              Invariant inv = (Invariant) l.next();
              if (inv.is_ni_suppressed())
                continue;
              inv.ppt = slice3;
              slice3.invs.add(inv);
            }
          }
          if (slice3.invs.size() > 0)
            ternary_slices.add(slice3);
        }
      }
    }
    addSlices(ternary_slices);
    if (debugMerge.isLoggable(Level.FINE))
      debug_print_slice_info(debugMerge, "ternary", ternary_slices);

    // Remove any merged invariants that are suppressed
    NIS.remove_suppressed_invs(this);

    // Remove the NI suppressed invariants in the children that we
    // previously created
    for (Iterator i = suppressed_invs.keySet().iterator(); i.hasNext();) {
      PptTopLevel child = (PptTopLevel) i.next();
      child.remove_invs((List) suppressed_invs.get(child));
    }

  }

  /**
   * Merges one child.  Since there is only one child, the merge
   * is trivial (each invariant can be just copied to the parent)
   */
  public void merge_invs_one_child() {

    // Fmt.pf ("merging single child of " + name);

    Assert.assertTrue(views.size() == 0);
    Assert.assertTrue(children.size() == 1);

    PptRelation rel = (PptRelation) children.get(0);

    // Loop through each slice
    slice_loop : for (Iterator i = rel.child.views_iterator(); i.hasNext();) {
      PptSlice cslice = (PptSlice) i.next();

      // Matching parent variable info.  Skip this slice if there isn't a
      // match for each variable (such as with an enter-exit relation)
      VarInfo[] pvis = parent_vis(rel, cslice);
      if (pvis == null)
        continue;
      VarInfo[] pvis_sorted = (VarInfo[]) pvis.clone();
      Arrays.sort(pvis_sorted, VarInfo.IndexComparator.getInstance());

      // Create the parent slice
      PptSlice pslice = null;
      if (pvis.length == 1)
        pslice = new PptSlice1(this, pvis_sorted[0]);
      else if (pvis.length == 2)
        pslice = new PptSlice2(this, pvis_sorted[0], pvis_sorted[1]);
      else
        pslice =
          new PptSlice3(this, pvis_sorted[0], pvis_sorted[1], pvis_sorted[2]);
      addSlice(pslice);

      // Build the permute from the child to the parent slice
      int[] permute = build_permute(pvis, pvis_sorted);
      // Fmt.pf ("Created parent slice " + pslice + " from child slice "
      //        + cslice + " with permute " + ArraysMDE.toString (permute));

      // Copy each child invariant to the parent
      for (int j = 0; j < cslice.invs.size(); j++) {
        Invariant child_inv = (Invariant) cslice.invs.get(j);
        Invariant parent_inv = child_inv.clone_and_permute(permute);
        parent_inv.ppt = pslice;
        pslice.invs.add(parent_inv);
        if (Debug.logOn())
          parent_inv.log("Added " + parent_inv.format() + " to " + pslice);
      }
    }

  }

  /**
   * Creates a list of parent variables that matches slice.  Returns
   * null if any of the variables don't have a corresponding parent
   * variables.  The corresponding parent variable can match ANY of
   * the members of an equality set.  For example, if the child EXIT
   * with variable A, with equality set members {A, orig(A)} is matched
   * against ENTER, A does not have a relation (since it is a post
   * value).  But orig(a) does have a relation.
   *
   * Note that there are cases where this is not exactly correct.
   * if you wanted to get all of the invariants over A where A
   * is an equality set with B, and A and B were in different
   * equality sets at the parent, the invariants true at A in  the
   * child are the union of those true at A and B at the
   * parent.
   */
  public VarInfo[] parent_vis(PptRelation rel, PptSlice slice) {

    VarInfo[] pvis = new VarInfo[slice.var_infos.length];
    for (int j = 0; j < slice.var_infos.length; j++) {
      VarInfo cv = slice.var_infos[j];
      VarInfo pv = null;
      for (Iterator k = cv.equalitySet.getVars().iterator(); k.hasNext();) {
        cv = (VarInfo) k.next();
        pv = rel.parentVar(cv);
        if (pv != null)
          break;
      }
      if (pv == null)
        return (null);
      if (!pv.isCanonical() && (cv == slice.var_infos[j])) {
        // Fmt.pf ("relations = " + rel.parent_to_child_var_string());
        Fmt.pf("pv.equalitySet = " + pv.equalitySet);
        Fmt.pf("cv.equalitySet = " + cv.equalitySet);
        Assert.assertTrue(
          pv.isCanonical(),
          "parent variable "
            + pv.name.name()
            + " for child variable "
            + cv.name.name()
            + " is not canonical "
            + " child = "
            + rel.child.name
            + " parent = "
            + rel.parent.name);
      }
      if (!pv.isCanonical())
        pv = pv.canonicalRep();
      pvis[j] = pv;

      // Assert.assertTrue (!pv.missingOutOfBounds());
    }
    return (pvis);
  }

  /**
   * Merges the conditionals from the children of this ppt to this ppt.
   * Only conditionals that exist at each child and whose splitting condition
   * is valid here can be merged.
   */
  public void merge_conditionals() {

    debugConditional.fine("attempting merge conditional for " + name());

    // If there are no children, there is nothing to do
    if (children.size() == 0)
      return;

    // If the children are only ppt => ppt_cond, there is nothing to do
    //     for (Iterator ii = children.iterator(); ii.hasNext(); ) {
    //       PptRelation rel = (PptRelation) ii.next();
    //       if (rel.getRelationType() == PptRelation.PPT_PPTCOND)
    //         return;
    //     }

    // If there are no splitters there is nothing to do
    if (!has_splitters())
      return;

    if (debugConditional.isLoggable(Level.FINE)) {
      debugConditional.fine("Merge conditional for " + name());
      for (int ii = 0; ii < children.size(); ii++) {
        PptRelation rel = (PptRelation) children.get(ii);
        debugConditional.fine("child: " + rel);
      }
    }

    // Merge the conditional points
    for (Iterator ii = cond_iterator(); ii.hasNext();) {
      PptConditional ppt_cond = (PptConditional) ii.next();
      if (debugConditional.isLoggable(Level.FINE)) {
        debugConditional.fine(
          "Merge invariants for conditional " + ppt_cond.name());
        for (int jj = 0; jj < ppt_cond.children.size(); jj++) {
          PptRelation rel = (PptRelation) ppt_cond.children.get(jj);
          debugConditional.fine("child relation: " + rel);
          debugConditional.fine(
            "child equality set = " + rel.child.equality_sets_txt());
        }
      }
      ppt_cond.mergeInvs();
      debugConditional.fine(
        "After merge, equality set = " + ppt_cond.equality_sets_txt());
    }
  }

  /**
   * Cleans up the ppt so that its invariants can be merged from other
   * ppts.  Not normally necessary unless the merge is taking place over
   * multiple ppts maps based on different data.  This allows a ppt to
   * have its invariants recalculated.
   */
  public void clean_for_merge() {
    equality_view = null;
    for (int i = 0; i < var_infos.length; i++)
      var_infos[i].equalitySet = null;
    views = new HashMap();
    // parents = new ArrayList();
    // children = new ArrayList();
    invariants_merged = false;
  }

  /**
   * Removes any invariant in this ppt which has a matching invariant in the
   * parent (as specified in the relation).  Done to save space.  Only safe
   * when all processing of this child is complete (ie, all of the parents
   * of this child must have been merged)
   *
   * Another interesting problem arises with this code.  As currently
   * setup, it won't process combined exit points (which often have two
   * parents), but it will process enter points.  Once the enter point
   * is removed, it can no longer parent filter the combined exit point.
   *
   * Also, the dynamic obvious code doesn't work anymore (because it is
   * missing the appropriate invariants).  This could be fixed by changing
   * dynamic obvious to search up the tree (blecho!).  Fix this by
   * only doing this for ppts whose parent only has one child
   */
  public void remove_child_invs(PptRelation rel) {

    // For now, only do this for ppts with only one parent
    // if (parents.size() != 1)
    //  return;

    // Only do this for ppts whose children have also been removed
    for (Iterator i = children.iterator(); i.hasNext();) {
      PptRelation crel = (PptRelation) i.next();
      if (!crel.child.invariants_removed) {
        // System.out.println ("Rel " + rel + "has not had its child invs rm");
        return;
      }
    }

    // Only do this for ppts whose parent only has one child
    // if (rel.parent.children.size() != 1)
    //  return;

    System.out.println("Removing child invariants at " + name());

    List /*PptSlice*/
    slices_to_remove = new ArrayList();

    // Loop through each slice
    slice_loop : for (Iterator i = views_iterator(); i.hasNext();) {
      PptSlice slice = (PptSlice) i.next();

      // Build the varlist for the parent.  If any variables are not present in
      // the parent, skip this slice
      VarInfo[] pvis = parent_vis(rel, slice);
      if (pvis == null)
        continue;
      VarInfo[] pvis_sorted = (VarInfo[]) pvis.clone();
      Arrays.sort(pvis_sorted, VarInfo.IndexComparator.getInstance());

      // Find the parent slice.  If it doesn't exist, there is nothing to do
      PptSlice pslice = rel.parent.findSlice(pvis_sorted);
      if (pslice == null)
        continue;

      // Build the permute from child to parent
      int[] permute = build_permute(pvis_sorted, pvis);

      // Remove any invariant that is also present in the parent
      for (Iterator j = slice.invs.iterator(); j.hasNext();) {
        Invariant orig_inv = (Invariant) j.next();
        Invariant inv = orig_inv.clone_and_permute(permute);
        Invariant pinv = pslice.find_inv_exact(inv);
        if (pinv != null) {
          j.remove();
          //System.out.println ("Removed " + orig_inv + " @" + name()
          //                    + " parent inv " + pinv + " @" + rel.parent);
        }
      }

      // If all of the invariants in a slice were removed, note it for removal
      if (slice.invs.size() == 0)
        slices_to_remove.add(slice);
    }

    // Remove all of the slices with 0 invariants
    System.out.println(
      "  Removed " + slices_to_remove.size() + " slices of " + views_size());
    for (Iterator i = slices_to_remove.iterator(); i.hasNext();) {
      PptSlice slice = (PptSlice) i.next();
      // System.out.println ("Removing Slice " + slice);
      removeSlice(slice);
    }

    invariants_removed = true;

  }
  /**
   * Builds a permutation from vis1 to vis2. The result is
   * vis1[i] = vis2[permute[i]]
   */
  public static int[] build_permute(VarInfo[] vis1, VarInfo[] vis2) {

    int[] permute = new int[vis1.length];
    boolean[] matched = new boolean[vis1.length];
    Arrays.fill(matched, false);

    for (int j = 0; j < vis1.length; j++) {
      for (int k = 0; k < vis2.length; k++) {
        if ((vis1[j] == vis2[k]) && (!matched[k])) {
          permute[j] = k;
          matched[k] = true; // don't match the same one twice
          break;
        }
      }
    }

    // Check results
    for (int j = 0; j < vis1.length; j++)
      Assert.assertTrue(vis1[j] == vis2[permute[j]]);

    return (permute);
  }

  public void debug_print_slice_info(
    Logger log,
    String descr,
    List /*PptSlice*/
  slices) {

    int inv_cnt = 0;
    for (int i = 0; i < slices.size(); i++)
      inv_cnt += ((PptSlice) slices.get(i)).invs.size();
    log.fine(slices.size() + descr + " slices with " + inv_cnt + " invariants");

  }

  /**
   * Returns true if all non-derived variables at this program point
   * are parameters.  Can be used to infer that a method is a constructor
   * ENTER point (since the objects fields are not included in that
   * case).  There is no guarantee, however, that that inference is
   * always correct
   */
  public boolean only_param_vars() {
    for (int i = 0; i < var_infos.length; i++) {
      VarInfo var = var_infos[i];
      if (!var.aux.getFlag(VarInfoAux.IS_PARAM) || var.isPrestate())
        return (false);
    }
    return (true);
  }

  /**
   * Insures that there are no invariants at this level that are duplicated
   * at the global level.
   */
  public boolean check_vs_global() {

    if (global == null)
      return (true);

    boolean ok = true;
    for (Iterator j = views_iterator(); j.hasNext();) {
      PptSlice slice = (PptSlice) j.next();
      PptSlice gslice = slice.find_global_slice(slice.var_infos);
      if (gslice == null)
        continue;
      for (int k = 0; k < slice.invs.size(); k++) {
        Invariant inv = (Invariant) slice.invs.get(k);
        Invariant ginv = gslice.find_inv_exact(inv);
        if ((ginv != null) && inv.isActive() && ginv.isActive()) {
          String cname = inv.getClass().getName();
          if ((cname.indexOf("Bound") == -1)
            && (cname.indexOf("OneOf") == -1)) {
            System.out.println(
              "inv "
                + inv
                + " in slice "
                + name()
                + " also appears at the global slice as "
                + ginv);
            ok = false;
          }
        }
      }
    }
    return (ok);
  }

  /**
   * Create the transforms between this point and the global ppt.
   * These transforms allow samples to be quickly created for the
   * global point and also allow invariants to be easily permuted
   * between the two points
   */

  public void init_global_transforms() {

    // We use this during post processing at all points, so the
    // following check is commented out.
    // Make sure this is a numbered exit point.
    //Assert.assertTrue (ppt_name.isExitPoint()
    //                   && !ppt_name.isCombinedExitPoint());

    // Look for matching names for each global variable in the child
    global_transform_post = new int[global.var_infos.length];
    Arrays.fill(global_transform_post, -1);
    for (int i = 0; i < global.var_infos.length; i++) {
      VarInfo vp = global.var_infos[i];
      for (int j = 0; j < var_infos.length; j++) {
        VarInfo vc = var_infos[j];
        if (vp.name == vc.name) {
          Assert.assertTrue(vp.varinfo_index == vp.value_index);
          Assert.assertTrue(vc.varinfo_index == vc.value_index);
          global_transform_post[vp.varinfo_index] = vc.varinfo_index;
          vc.global_index = (short) vp.varinfo_index;
          break;
        }
      }
    }

    // Look for orig versions of each non-derived global variable in the child
    global_transform_orig = new int[global.var_infos.length];
    Arrays.fill(global_transform_orig, -1);
    for (int i = 0; i < global.var_infos.length; i++) {
      VarInfo vp = global.var_infos[i];
      if (vp.derived != null)
        continue;
      VarInfoName orig_name = vp.name.applyPrestate().intern();
      for (int j = 0; j < var_infos.length; j++) {
        VarInfo vc = var_infos[j];
        if (orig_name == vc.name) {
          Assert.assertTrue(vp.varinfo_index == vp.value_index);
          Assert.assertTrue(vc.varinfo_index == vc.value_index);
          global_transform_orig[vp.varinfo_index] = vc.varinfo_index;
          vc.global_index = (short) vp.varinfo_index;
          break;
        }
      }
    }

    // Look for orig versions of derived variables in the child.  This is
    // done by finding the base of each derived variable and looking for
    // a child variable with the same bases and the same equation.  This
    // is necessary because derivations are done AFTER orig variables so
    // applying the prestate name (as done above) won't work (the resulting
    // variable is really the same but the name is constructed differently)

    // TODO

    // Debug print the transforms
    if (debugGlobal.isLoggable(Level.FINE)) {
      debugGlobal.fine("orig transform at " + name());
      for (int i = 0; i < global_transform_orig.length; i++) {
        if (global_transform_orig[i] == -1)
          debugGlobal.fine(
            "-- " + global.var_infos[i].name.name() + " : NO MATCH");
        else
          debugGlobal.fine(
            "-- "
              + global.var_infos[i].name.name()
              + " : "
              + var_infos[global_transform_orig[i]].name.name());
      }
      debugGlobal.fine("post transform at " + name());
      for (int i = 0; i < global_transform_post.length; i++) {
        if (global_transform_post[i] == -1)
          debugGlobal.fine(
            "-- " + global.var_infos[i].name.name() + " : NO MATCH");
        else
          debugGlobal.fine(
            "-- "
              + global.var_infos[i].name.name()
              + " : "
              + var_infos[global_transform_post[i]].name.name());
      }
    }
  }

  /**
   * Transform a sample to the global ppt using the specified transform.
   * transform[i] returns the value_index in this ppt that corresponds to
   * index i in the global ppt
   **/
  public ValueTuple transform_sample(int[] transform, ValueTuple vt) {

    Object[] vals = new Object[global.var_infos.length];
    int[] mods = new int[global.var_infos.length];
    Arrays.fill(mods, ValueTuple.MISSING_FLOW);
    for (int i = 0; i < transform.length; i++) {
      if (transform[i] != -1) {
        vals[i] = vt.vals[transform[i]];
        mods[i] = vt.mods[transform[i]];
      }
    }
    return (new ValueTuple(vals, mods));
  }

  /**
   * Returns the local variable that corresponds to the specified global
   * variable via the post transform.
   */
  public VarInfo local_postvar(VarInfo global_var) {

    return var_infos[global_transform_post[global_var.varinfo_index]];
  }

  /**
   * Returns the local variable that corresponds to the specified global
   * variable via the orig transform.
   */
  public VarInfo local_origvar(VarInfo global_var) {

    return var_infos[global_transform_orig[global_var.varinfo_index]];
  }

  /**
   * Create an equality invariant over the specified variables.  Samples
   * should be the number of samples for the slice over v1 and v2.  The
   * slice should not already exist.
   */
  public PptSlice create_equality_inv(VarInfo v1, VarInfo v2, int samples) {

    ProglangType rep = v1.rep_type;
    boolean rep_is_scalar = rep.isScalar();
    boolean rep_is_float = rep.isFloat();

    Assert.assertTrue(findSlice_unordered(v1, v2) == null);
    PptSlice newSlice = get_or_instantiate_slice(v1, v2);

    // Copy over the number of samples from this to the new slice,
    // so that all invariants on the slice report the right number
    // of samples.
    newSlice.set_samples(samples);

    Invariant invEquals = null;

    // This is almost directly copied from PptSlice2's instantiation
    // of factories
    if (rep_is_scalar) {
      invEquals = IntEqual.get_proto().instantiate(newSlice);
    } else if ((rep == ProglangType.STRING)) {
      invEquals = StringEqual.get_proto().instantiate(newSlice);
    } else if ((rep == ProglangType.INT_ARRAY)) {
      invEquals = SeqSeqIntEqual.get_proto().instantiate(newSlice);
    } else if ((rep == ProglangType.STRING_ARRAY)) {
      // JHP commented out to see what diffs are coming from here (5/3/3)
      //         invEquals = SeqComparisonString.instantiate (newSlice, true);
      //         if (invEquals != null) {
      //           ((SeqComparisonString) invEquals).can_be_eq = true;
      //         }
      //         debugPostProcess.fine ("  seqStringEqual");
    } else if (Daikon.dkconfig_enable_floats) {
      if (rep_is_float) {
        invEquals = FloatEqual.get_proto().instantiate(newSlice);
      } else if (rep == ProglangType.DOUBLE_ARRAY) {
        invEquals = SeqSeqFloatEqual.get_proto().instantiate(newSlice);
      }
    } else {
      throw new Error("No known Comparison invariant to convert equality into");
    }

    if (invEquals != null) {
      newSlice.addInvariant(invEquals);
    } else {
      if (newSlice.invs.size() == 0)
        newSlice.parent.removeSlice(newSlice);
    }
    return (newSlice);
  }

  public static void count_unique_slices(Logger log, PptMap all_ppts) {

    Map slices = new LinkedHashMap(10000);

    int slice_cnt = 0;
    int ppt_cnt = 0;

    // Loop through each ppt
    for (Iterator ii = all_ppts.pptIterator(); ii.hasNext();) {
      PptTopLevel ppt = (PptTopLevel) ii.next();
      if (ppt == all_ppts.getGlobal())
        continue;
      if (!ppt.ppt_name.isExitPoint())
        continue;
      if (ppt.ppt_name.isCombinedExitPoint())
        continue;
      ppt_cnt++;

      // Loop through each ternary slice
      slice_loop : for (Iterator jj = ppt.views_iterator(); jj.hasNext();) {
        PptSlice slice = (PptSlice) jj.next();
        if (slice.arity() != 3)
          continue;
        for (int kk = 0; kk < slice.var_infos.length; kk++) {
          if (!slice.var_infos[kk].is_global()) {
            continue slice_loop;
          }
        }

        slice_cnt++;
        SliceMatch sm_new = new SliceMatch(slice);
        SliceMatch sm_old = (SliceMatch) slices.get(sm_new);
        if (sm_old != null)
          sm_old.all_slices.add(slice);
        else
          slices.put(sm_new, sm_new);
      }
    }

    int max_out = 1000;
    System.out.println(
      slice_cnt
        + " slices considered over "
        + ppt_cnt
        + " ppts, "
        + slices.size()
        + " unique slices in map");
    for (Iterator ii = slices.values().iterator(); ii.hasNext();) {
      SliceMatch sm = (SliceMatch) ii.next();
      log.fine("Slice occurs " + sm.all_slices.size() + " times");
      for (int jj = 0; jj < sm.all_slices.size(); jj++) {
        PptSlice slice = (PptSlice) sm.all_slices.get(jj);
        log.fine(": " + slice);
        for (int kk = 0; kk < slice.invs.size(); kk++) {
          Invariant inv = (Invariant) slice.invs.get(kk);
          log.fine(": : " + inv.format());
        }
      }
      if (--max_out <= 0)
        break;
    }
  }

  public static void count_unique_inv_lists(Logger log, PptMap all_ppts) {

    Map slices = new LinkedHashMap(10000);

    int inv_list_cnt = 0;
    int ppt_cnt = 0;

    // Loop through each ppt
    for (Iterator ii = all_ppts.pptIterator(); ii.hasNext();) {
      PptTopLevel ppt = (PptTopLevel) ii.next();
      if (ppt == all_ppts.getGlobal())
        continue;
      if (!ppt.ppt_name.isExitPoint())
        continue;
      if (ppt.ppt_name.isCombinedExitPoint())
        continue;
      ppt_cnt++;

      // Loop through each ternary slice
      for (Iterator jj = ppt.views_iterator(); jj.hasNext();) {
        PptSlice slice = (PptSlice) jj.next();
        if (slice.arity() != 3)
          continue;

        inv_list_cnt++;
        InvListMatch invs_new = new InvListMatch(slice.invs);
        InvListMatch invs_old = (InvListMatch) slices.get(invs_new);
        if (invs_old != null)
          invs_old.all_invs.add(slice.invs);
        else
          slices.put(invs_new, invs_new);
      }
    }

    int max_out = 100;
    System.out.println(
      inv_list_cnt
        + " slices considered over "
        + ppt_cnt
        + " ppts, "
        + slices.size()
        + " unique slices in map");
    if (true)
      return;
    for (Iterator ii = slices.values().iterator(); ii.hasNext();) {
      InvListMatch ilm = (InvListMatch) ii.next();
      if (ilm.all_invs.size() > 1)
        continue;
      log.fine("Slice occurs " + ilm.all_invs.size() + " times");
      for (int jj = 0; jj < ilm.all_invs.size(); jj++) {
        Invariants invs = (Invariants) ilm.all_invs.get(jj);
        PptSlice slice = ((Invariant) invs.get(0)).ppt;
        log.fine(": " + slice);
        for (int kk = 0; kk < invs.size(); kk++) {
          Invariant inv = (Invariant) invs.get(kk);
          log.fine(": : " + inv.format());
        }
      }
      if (--max_out <= 0)
        break;
    }
  }

  /**
   * Provides hashcode/equal functions for slices over global variables
   * regardless of what ppt they are in.
   */
  public static class SliceMatch {

    PptSlice slice = null;
    public List all_slices = new ArrayList();

    public SliceMatch(PptSlice slice) {
      this.slice = slice;
      all_slices.add(slice);
    }

    public boolean equals(Object obj) {
      if (!(obj instanceof SliceMatch))
        return (false);

      SliceMatch sm = (SliceMatch) obj;
      PptSlice s1 = slice;
      PptSlice s2 = sm.slice;

      // Make sure they both refer to the same gobal variables via the same
      // transform
      if (s1.var_infos.length != s2.var_infos.length)
        return (false);
      for (int ii = 0; ii < s1.var_infos.length; ii++) {
        VarInfo v1 = s1.var_infos[ii];
        VarInfo v2 = s2.var_infos[ii];
        if (!v1.is_global() || !v2.is_global())
          return (false);
        if (v1.global_var() != v2.global_var())
          return (false);
        if (v1.is_post_global() != v2.is_post_global())
          return (false);
      }

      // Make sure that each invariant matches exactly
      if (s1.invs.size() != s2.invs.size())
        return (false);
      for (int ii = 0; ii < s1.invs.size(); ii++) {
        Invariant inv1 = (Invariant) s1.invs.get(ii);
        Invariant inv2 = (Invariant) s2.invs.get(ii);
        if (inv1.getClass() != inv2.getClass())
          return (false);
        if (!inv1.isSameFormula(inv2))
          return (false);
      }

      return (true);
    }

    public int hashCode() {

      int code = 0;

      // include a hash over the equivalent global vars
      for (int ii = 0; ii < slice.var_infos.length; ii++) {
        VarInfo gvar = slice.var_infos[ii].global_var();
        if (gvar == null)
          code += slice.var_infos[ii].hashCode();
        else
          code += gvar.hashCode();
      }

      // hash over the invariants.  This should be better for function
      // binary invariants
      for (int ii = 0; ii < slice.invs.size(); ii++) {
        code += slice.invs.get(ii).getClass().hashCode();
      }

      return (code);
    }
  }

  /**
   * Provides hashcode/equal functions for invariant lists regardless
   * of their ppt.
   */
  public static class InvListMatch {

    Invariants invs = null;
    public List all_invs = new ArrayList();

    public InvListMatch(Invariants invs) {
      this.invs = invs;
      all_invs.add(invs);
    }

    public boolean equals(Object obj) {
      if (!(obj instanceof InvListMatch))
        return (false);

      InvListMatch ilm = (InvListMatch) obj;
      Invariants invs1 = invs;
      Invariants invs2 = ilm.invs;

      // Make sure that each invariant matches exactly
      if (invs1.size() != invs2.size())
        return (false);
      for (int ii = 0; ii < invs1.size(); ii++) {
        Invariant inv1 = (Invariant) invs1.get(ii);
        Invariant inv2 = (Invariant) invs2.get(ii);
        if (inv1.getClass() != inv2.getClass())
          return (false);
        if (!inv1.isSameFormula(inv2))
          return (false);
      }

      return (true);
    }

    public int hashCode() {

      int code = 0;

      // hash over the invariants.  This should be better for function
      // binary invariants
      for (int ii = 0; ii < invs.size(); ii++) {
        code += invs.get(ii).getClass().hashCode();
      }

      return (code);
    }
  }

  /**
   * Stores various statistics about a ppt.
   */
  public static class Stats {

    /** sample count **/
    public int sample_cnt = 0;

    /** number of equality sets **/
    public int set_cnt = 0;

    /** total number of variables in all equality sets **/
    public int var_cnt = 0;

    /** time (milliseconds) to process this sample **/
    public int time = 0;

    /** additional memory (bytes) allocated to processing this sample **/
    public long memory = 0;

    /** number of invariants **/
    public int inv_cnt = 0;

    /** number of slices **/
    public int slice_cnt = 0;

    /** number of instantiated invariants before the sample is applied **/
    public int instantiated_inv_cnt = 0;

    /** number of instantiated slices **/
    public int instantiated_slice_cnt = 0;

    /** total number of global invariants that have flowed **/
    public int tot_invs_flowed = 0;

    /** global invariants still in the list to flow **/
    public int invs_to_flow = 0;

    /** program point of the stat **/
    public PptTopLevel ppt;

    int const_slice_cnt = 0;
    int const_inv_cnt = 0;
    int constant_leader_cnt = 0;
    public static boolean cnt_inv_classes = false;
    Map inv_map = null;
    public static boolean show_invs = false;
    public static boolean show_tern_slices = false;

    /**
     * Sets each of the stats from the current info in ppt and the specified
     * time (msecs) and memory (bytes).
     */
    void set(PptTopLevel ppt, int time, int memory) {
      set_cnt = 0;
      var_cnt = 0;
      if (ppt.equality_view != null) {
        for (int j = 0; j < ppt.equality_view.invs.size(); j++) {
          set_cnt++;
          Equality e = (Equality) ppt.equality_view.invs.get(j);
          Collection vars = e.getVars();
          var_cnt += vars.size();
        }
      }
      this.ppt = ppt;
      sample_cnt = ppt.num_samples();
      slice_cnt = ppt.slice_cnt();
      const_slice_cnt = ppt.const_slice_cnt();
      const_inv_cnt = ppt.const_inv_cnt();
      inv_cnt = ppt.invariant_cnt();
      instantiated_slice_cnt = ppt.instantiated_slice_cnt;
      instantiated_inv_cnt = ppt.instantiated_inv_cnt;
      tot_invs_flowed = global_weakened_invs.size();
      invs_to_flow = global_weakened_invs.size() - global_weakened_start_index;
      if (ppt.constants != null)
        constant_leader_cnt = ppt.constants.constant_leader_cnt();
      this.time = time;
      this.memory = memory;

      if (cnt_inv_classes)
        inv_map = ppt.invariant_cnt_by_class();
    }

    static void dump_header(Logger log) {

      log.fine(
        "Program Point : Sample Cnt: Equality Cnt : Var Cnt : "
          + " Vars/Equality : Const Slice Cnt :  "
          + " Slice /  Inv Cnt : Instan Slice / Inv Cnt "
          + " Memory (bytes) : Time (msecs) ");
    }

    void dump(Logger log) {

      DecimalFormat dfmt = new DecimalFormat();
      dfmt.setMaximumFractionDigits(2);
      dfmt.setGroupingSize(3);
      dfmt.setGroupingUsed(true);

      double vars_per_eq = 0;
      if (set_cnt > 0)
        vars_per_eq = (double) var_cnt / set_cnt;

      log.fine(
        ppt.name()
          + " : "
          + sample_cnt
          + " : "
          + set_cnt
          + " ("
          + constant_leader_cnt
          + " con) : "
          + var_cnt
          + " : "
          + dfmt.format(vars_per_eq)
          + " : "
          + const_slice_cnt
          + "/"
          + const_inv_cnt
          + " : "
          + slice_cnt
          + "/"
          + inv_cnt
          + " : "
          + instantiated_slice_cnt
          + "/"
          + instantiated_inv_cnt
          + " : "
          + tot_invs_flowed
          + "/"
          + invs_to_flow
          + ": "
          + memory
          + ": "
          + time);
      if (cnt_inv_classes) {
        for (Iterator i = inv_map.keySet().iterator(); i.hasNext();) {
          Class inv_class = (Class) i.next();
          Cnt cnt = (Cnt) inv_map.get(inv_class);
          log.fine(" : " + inv_class + ": " + cnt.cnt);
        }
      }

      if (show_invs) {
        for (Iterator j = ppt.views_iterator(); j.hasNext();) {
          PptSlice slice = (PptSlice) j.next();
          for (int k = 0; k < slice.invs.size(); k++) {
            Invariant inv = (Invariant) slice.invs.get(k);
            String falsify = "";
            if (inv.is_false())
              falsify = "(falsified) ";
            log.fine(" : " + falsify + inv.format());
          }
        }
      }

      if (show_tern_slices) {
        for (Iterator j = ppt.views_iterator(); j.hasNext();) {
          PptSlice slice = (PptSlice) j.next();
          StringBuffer sb = new StringBuffer();
          for (int k = 0; k < slice.arity(); k++) {
            VarInfo v = slice.var_infos[k];
            sb.append(
              v.name.name()
                + "/"
                + v.equalitySet.getVars().size()
                + "/"
                + v.file_rep_type
                + " ");
          }
          log.fine(": " + sb.toString() + ": " + slice.invs.size());
        }
      }

    }
  }

  /**
   * Print statistics concerning equality sets over the entire set of
   * ppts to the specified logger.
   */
  public static void print_equality_stats(Logger log, PptMap all_ppts) {

    if (!log.isLoggable(Level.FINE))
      return;
    boolean show_details = true;

    NumberFormat dfmt = NumberFormat.getInstance();
    dfmt.setMaximumFractionDigits(2);
    double equality_set_cnt = 0;
    double vars_cnt = 0;
    double total_sample_cnt = 0;
    Map stats_map = Global.stats_map;

    Stats.dump_header(debug);
    for (Iterator i = all_ppts.pptIterator(); i.hasNext();) {
      PptTopLevel ppt = (PptTopLevel) i.next();
      List slist = (List) stats_map.get(ppt);
      if (slist == null)
        continue;
      int sample_cnt = 0;
      int time = 0;
      double avg_equality_cnt = 0;
      double avg_var_cnt = 0;
      double avg_vars_per_equality = 0;
      double avg_inv_cnt = 0;
      int instantiated_inv_cnt = 0;
      int slice_cnt = 0;
      int instantiated_slice_cnt = 0;
      long memory = 0;
      if (slist != null) {
        sample_cnt = slist.size();
        total_sample_cnt += sample_cnt;
        for (int j = 0; j < slist.size(); j++) {
          Stats stats = (Stats) slist.get(j);
          avg_equality_cnt += stats.set_cnt;
          avg_var_cnt += stats.var_cnt;
          equality_set_cnt += stats.set_cnt;
          vars_cnt += stats.var_cnt;
          time += stats.time;
          avg_inv_cnt += stats.inv_cnt;
          slice_cnt += stats.slice_cnt;
          instantiated_inv_cnt += stats.instantiated_inv_cnt;
          instantiated_slice_cnt += stats.instantiated_slice_cnt;
          memory += stats.memory;
        }
        avg_equality_cnt = avg_equality_cnt / sample_cnt;
        avg_var_cnt = avg_var_cnt / sample_cnt;
      }
      if (avg_equality_cnt > 0)
        avg_vars_per_equality = avg_var_cnt / avg_equality_cnt;
      log.fine(
        ppt.name()
          + " : "
          + sample_cnt
          + " : "
          + dfmt.format(avg_equality_cnt)
          + " : "
          + dfmt.format(avg_var_cnt)
          + " : "
          + dfmt.format(avg_vars_per_equality)
          + " : "
          + dfmt.format((double) slice_cnt / sample_cnt)
          + "/"
          + dfmt.format((double) avg_inv_cnt / sample_cnt)
          + " : "
          + dfmt.format((double) instantiated_slice_cnt / sample_cnt)
          + "/"
          + dfmt.format((double) instantiated_inv_cnt / sample_cnt)
          + ": "
          + dfmt.format((double) memory / sample_cnt)
          + ": "
          + dfmt.format((double) time / sample_cnt));
      if (show_details) {
        double avg_time = (double) time / sample_cnt;
        for (int j = 0; j < slist.size(); j++) {
          Stats stats = (Stats) slist.get(j);
          double vars_per_eq = 0;
          if (stats.set_cnt > 0)
            vars_per_eq = (double) stats.var_cnt / stats.set_cnt;
          if ((j == (slist.size() - 1)) || (stats.time > (2 * avg_time)))
            log.fine(
              " : "
                + j
                + " : "
                + stats.set_cnt
                + " : "
                + stats.var_cnt
                + " : "
                + dfmt.format(vars_per_eq)
                + " : "
                + stats.slice_cnt
                + "/"
                + stats.inv_cnt
                + " : "
                + stats.instantiated_slice_cnt
                + "/"
                + stats.instantiated_inv_cnt
                + " : "
                + stats.memory
                + ": "
                + stats.time);
        }
      }
    }
  }

  /** sets the sample count **/
  void set_sample_number(int val) {
    values_num_samples = val;
  }

  /**
  * Increments the number of samples processed by the program point by 1
  */
  //added by Chen 6/14/04 for use with the simple incremental algorithm
  public void incSampleNumber() {
    values_num_samples++;
  }

}
