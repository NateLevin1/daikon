package daikon.inv.filter;

import daikon.inv.*;
import daikon.inv.filter.*;
import daikon.VarInfo;
import daikon.PrintInvariants;

class UnmodifiedVariableEqualityFilter extends InvariantFilter {
  public String getDescription() {
    return "Suppress invariants that merely indicate that a variable was unmodified";
  }

  boolean shouldDiscardInvariant( Invariant invariant ) {
    if (PrintInvariants.debugFiltering.isDebugEnabled()) {
      PrintInvariants.debugFiltering.debug("\t\tEntering UmVEF.shouldDiscard\n");
    }

    if (!IsEqualityComparison.it.accept(invariant)) {
      if (PrintInvariants.debugFiltering.isDebugEnabled()) {
	PrintInvariants.debugFiltering.debug("\t\tUnmodVarEqF thinks this isn't an equality comparison\n");
      }
      return false;
    }

    Comparison comp = (Comparison)invariant;
    VarInfo var1 = comp.var1();
    VarInfo var2 = comp.var2();

    if (PrintInvariants.debugFiltering.isDebugEnabled()) {
      PrintInvariants.debugFiltering.debug("\tcompared " + var1.name.applyPrestate() + " to " + var2.name.toString() + "\n");
    }

    if (var1.name.applyPrestate().equals(var2.name)) {
      if (PrintInvariants.debugFiltering.isDebugEnabled()) {
	PrintInvariants.debugFiltering.debug("\t\t(yes...)\n");
      }
      return(true);
    }

    if (PrintInvariants.debugFiltering.isDebugEnabled()) {
      PrintInvariants.debugFiltering.debug("\tcompared " + var2.name.applyPrestate() + " to " + var1.name.toString() + "\n");
    }

    if (var2.name.applyPrestate().equals(var1.name)) {
      if (PrintInvariants.debugFiltering.isDebugEnabled()) {
	PrintInvariants.debugFiltering.debug("\t\t(yes...)\n");
      }
      return(true);
    }

    return false;
  }
}
