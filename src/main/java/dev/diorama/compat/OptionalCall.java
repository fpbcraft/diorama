package dev.diorama.compat;

import java.lang.invoke.*;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;

/** Resolves one optional method without resolving unrelated client-only overloads on a server. */
final class OptionalCall {
	private final MethodHandle handle;
	private final boolean isStatic;
	private OptionalCall(MethodHandle handle, boolean isStatic) {this.handle=handle;this.isStatic=isStatic;}
	static OptionalCall virtual(Class<?> owner,String name,Class<?> result,Class<?>...parameters) throws ReflectiveOperationException {
		return new OptionalCall(MethodHandles.publicLookup().findVirtual(owner,name,MethodType.methodType(result,parameters)),false);
	}
	static OptionalCall statik(Class<?> owner,String name,Class<?> result,Class<?>...parameters) throws ReflectiveOperationException {
		return new OptionalCall(MethodHandles.publicLookup().findStatic(owner,name,MethodType.methodType(result,parameters)),true);
	}
	Object invoke(Object receiver,Object...arguments) throws ReflectiveOperationException {
		var values=new ArrayList<Object>();if(!isStatic)values.add(receiver);values.addAll(Arrays.asList(arguments));
		try{return handle.invokeWithArguments(values);}
		catch(RuntimeException|Error error){throw error;}
		catch(Throwable error){throw new InvocationTargetException(error);}
	}
}
