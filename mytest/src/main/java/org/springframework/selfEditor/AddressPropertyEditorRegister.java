package org.springframework.selfEditor;

import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;

/**
 * @projectName: spring
 * @package: org.springframework.selfEditor
 * @className: AddressPropertyEditorRegister
 * @author: Eric
 * @description: TODO
 * @date: 2023/11/30 12:02
 * @version: 1.0
 */
public class AddressPropertyEditorRegister implements PropertyEditorRegistrar {
	@Override
	public void registerCustomEditors(PropertyEditorRegistry registry) {
		registry.registerCustomEditor(Address.class,new AddressPropertyEditor());
	}
}
