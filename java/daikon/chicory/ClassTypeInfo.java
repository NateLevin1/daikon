package daikon.chicory;

import java.util.EnumSet;

// var must be Class<?>
// this will output a synthetic variable var.getType()
public class ClassTypeInfo extends DaikonVariableInfo {
  public ClassTypeInfo(
      String theName, String typeName, String repTypeName, String function_args, boolean isArr) {
    super(theName, typeName, repTypeName, isArr);

    this.function_args = function_args;
  }

  // .getType() variables are derived, so just keep the parent value
  @Override
  public Object getMyValFromParentVal(Object value) {
    return value;
  }

  @Override
  public String getDTraceValueString(Object val) {
    if (isArray) {
      // FIXME
      System.err.println("FIXME: cannot ClassNameInfo#getDTraceValueString an array yet");
      return "nonsensical" + DaikonWriter.lineSep + "2";
    } else {
      assert val instanceof Class<?>;
      return getValueStringNonArr((Class<?>) val);
    }
  }

  public String getValueStringNonArr(Class<?> val) {
    String valString;

    if (val == null) {
      valString = "nonsensical" + DaikonWriter.lineSep + "2";
    } else {
      valString = ("\"" + DTraceWriter.stdClassName(val) + "\"") + DaikonWriter.lineSep + "1";
    }

    return valString;
  }

  /** Returns function since essentially this is a call to a pure function. */
  @Override
  public VarKind get_var_kind() {
    return VarKind.FUNCTION;
  }

  /** Returns the name of this field. */
  @Override
  public String get_relative_name() {
    return "getType()";
  }

  @Override
  public EnumSet<VarFlags> get_var_flags() {
    EnumSet<VarFlags> flags = super.get_var_flags();
    flags.add(VarFlags.SYNTHETIC);
    // FIXME: this adds quotes around everything, just so we get the benefit of OneOfString support
    flags.add(VarFlags.TO_STRING);
    flags.add(VarFlags.NON_NULL);
    return flags;
  }
}
