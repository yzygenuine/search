/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.annotations.processors;

import com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.annotations.ValueProcessor;

/**
 * Processes double values.
 *
 * @author Eike Bergmann
 *
 */
public class DoubleProcessor implements ValueProcessor<Double> {
	/**
	 * Converts value into a double using {@link Double#parseDouble(String)}
	 *
	 * @return Double the result
	 */
	@Override
	public Double processValue(String value) {
		return Double.parseDouble(value);
	}
}