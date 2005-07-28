package daikon.chicory;

import java.util.*;

/**
 * NonsensicalList is similar to NonsensicalObject but it is used for
 * arrays whose value is nonsensical.
 */
public class NonsensicalList extends AbstractList implements List
{

    /**
     *
     */
    private NonsensicalList()
    {
        super();
    }

    public static NonsensicalList getInstance()
    {
        return theList;
    }

    public Object get(int index)
    {
        return NonsensicalObject.getInstance();
    }

    public int size()
    {
        return -1;
    }

    public String toString()
    {
        return "NonsensicalList";
    }

    private final static NonsensicalList theList = new NonsensicalList();
}
