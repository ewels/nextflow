/*
 * Copyright 2024-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.config.scopes;

import nextflow.config.schema.ConfigOption;
import nextflow.config.schema.ConfigScope;
import nextflow.script.dsl.Description;

public class TimelineConfig implements ConfigScope {

    @ConfigOption
    @Description("""
        When `true` enables the generation of the timeline report file (default: `false`).
    """)
    public boolean enabled;

    @ConfigOption
    @Description("""
        Timeline file name (default: `'timeline-<timestamp>.html'`).
    """)
    public String file;

    @ConfigOption
    @Description("""
        When `true` overwrites any existing timeline file with the same name.
    """)
    public boolean overwrite;

}
