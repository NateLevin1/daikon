// ***** This file is automatically generated from Functions.java.jpp

package daikon.inv;

import utilMDE.*;

public final class Functions  {

  public final static String[] unaryFunctionNames;
//  public final static Method[] unaryFunctions;

  public final static String[] binarySymmetricFunctionNames;
//  public final static Method[] binarySymmetricFunctions;

  public final static String[] binaryNonSymmetricFunctionNames;
//  public final static Method[] binaryNonSymmetricFunctions;

//  private static Method[] methodNamesToMethods(String[] names) {
//    try {
//      Method[] result = new Method[names.length];
//      for (int i=0; i<unaryFunctionNames.length; i++)
//        result[i] = UtilMDE.methodForName(names[i]);
//      return result;
//    } catch (Exception e) {
//      e.printStackTrace();
//      throw new Error(e.toString());
//    }
//  }

  static {

    // I need to have the names available so the methods can be serialized.

    unaryFunctionNames = new String[] {
      /// Java language operators (in precedence order)
      // increment: subsumed by LinearBinary
      // decrement: subsumed by LinearBinary

      "utilMDE.MathMDE.bitwiseComplement(int)"

      // logicalComplement: subsumed by LinearBinary
      //"utilMDE.MathMDE.negate(" + "int"  + ")",
      /// Non-operators
    };
  //  unaryFunctions = methodNamesToMethods(unaryFunctionNames);

    binarySymmetricFunctionNames = new String[] {
      /// Java language operators (in precedence order, omitting boolean operators)
      // Maybe instead of mul I should have a specific invariant that also
      // looks for a leading constant.
      "utilMDE.MathMDE.mul(" + "int"  +"," + "int"  +")",
      // plus: subsumed by LinearTernary.

      "utilMDE.MathMDE.bitwiseAnd(int,int)",
      "utilMDE.MathMDE.logicalAnd(int,int)",
      "utilMDE.MathMDE.bitwiseXor(int,int)",
      "utilMDE.MathMDE.logicalXor(int,int)",
      "utilMDE.MathMDE.bitwiseOr(int,int)",
      "utilMDE.MathMDE.logicalOr(int,int)",

      /// Non-operators.
      "java.lang.Math.min(" + "int"  + "," + "int"  + ")",
      "java.lang.Math.max(" + "int"  + "," + "int"  + ")"

     , "utilMDE.MathMDE.gcd(int,int)"

    };
    //binarySymmetricFunctions = methodNamesToMethods(binarySymmetricFunctionNames);

    binaryNonSymmetricFunctionNames = new String[] {
      /// Java language operators (in precedence order, omitting boolean operators)
      "utilMDE.MathMDE.div(" + "int"  + "," + "int"  + ")",

      "utilMDE.MathMDE.mod(int,int)",
      // minus: subsumed by LinearTernary
      // (Are the shifts also subsumed by LinearTernary?)
      "utilMDE.MathMDE.lshift(int,int)",
      "utilMDE.MathMDE.rshiftSigned(int,int)",
      "utilMDE.MathMDE.rshiftUnsigned(int,int)",
      /// Non-operators.
      "utilMDE.MathMDE.pow(int,int)"

      // MathMDE_cmp = "utilMDE.MathMDE.cmp(int,int)"
      // MathMDE_cmp = "utilMDE.MathMDE.round(int,int)"
    };
//    binaryNonSymmetricFunctions = methodNamesToMethods(binaryNonSymmetricFunctionNames);

  }

  public static long  invokeUnary(int methodnumber, long  arg) {

    switch(methodnumber) {
    case 0: return MathMDE.bitwiseComplement(arg);
    case 1: return MathMDE.negate(arg);
    case 2: return Math.abs(arg);
    }

    System.out.println("returning 0, unary");
    return 0;
  }

  public static long  invokeBinary(int methodnumber, long  arg, long  arg2) {

    switch(methodnumber) {
    case 0:  return MathMDE.mul(arg, arg2);
    case 1:  return MathMDE.bitwiseAnd(arg, arg2);
    case 2:  return MathMDE.logicalAnd(arg, arg2);
    case 3:  return MathMDE.bitwiseXor(arg, arg2);
    case 4:  return MathMDE.logicalXor(arg, arg2);
    case 5:  return MathMDE.bitwiseOr(arg, arg2);
    case 6:  return MathMDE.logicalOr(arg, arg2);
    case 7:  return Math.min(arg, arg2);
    case 8:  return Math.max(arg, arg2);
    case 9:  return MathMDE.gcd(arg, arg2);
    case 10: return MathMDE.div(arg, arg2);
    case 11: return MathMDE.mod(arg, arg2);
    case 12: return MathMDE.lshift(arg, arg2);
    case 13: return MathMDE.rshiftSigned(arg, arg2);
    case 14: return MathMDE.rshiftUnsigned(arg, arg2);
    case 15: return MathMDE.pow(arg, arg2);
    }

    System.out.println("returning 0 binary");
    return 0;
  }

  public static int lookup(String methodname) {
    System.out.println("looking up");
    return 0;
  }

  // don't permit instantiation
  private Functions () { }

}
