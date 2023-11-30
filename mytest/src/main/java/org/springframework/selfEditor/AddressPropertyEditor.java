package org.springframework.selfEditor;

import java.beans.PropertyEditorSupport;

/**
 * @projectName: spring
 * @package: org.springframework.selfEditor
 * @className: AddressPropertyEditor
 * @author: Eric
 * @description: TODO
 * @date: 2023/11/30 11:57
 * @version: 1.0
 */
public class AddressPropertyEditor extends PropertyEditorSupport {
	@Override
	public void setAsText(String text) throws IllegalArgumentException {
		String[] sp = text.split("_");
		Address address = new Address();
		address.setCity(sp[1]);
		address.setTown(sp[0]);
		address.setProvince(sp[2]);
		this.setValue(address);
	}
}
