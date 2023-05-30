package datawave.test;

import datawave.query.common.grouping.GroupingAttribute;
import datawave.query.common.grouping.Groups;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.util.Sets;

import java.util.Arrays;
import java.util.Set;

public class GroupsAssert extends AbstractAssert<GroupsAssert,Groups> {
    
    public static GroupsAssert assertThat(Groups groups) {
        return new GroupsAssert(groups);
    }
    
    protected GroupsAssert(Groups groups) {
        super(groups, GroupsAssert.class);
    }
    
    public GroupsAssert hasTotalGroups(int total) {
        isNotNull();
        if (total != actual.totalGroups()) {
            failWithMessage("Expected %s total groups, but was %s", total, actual.totalGroups());
        }
        return this;
    }
    
    public GroupAssert assertGroup(GroupingAttribute<?>... keyElements) {
        isNotNull();
        Set<GroupingAttribute<?>> key = Sets.newHashSet();
        key.addAll(Arrays.asList(keyElements));
        return GroupAssert.assertThat(actual.getGroup(key));
    }
    
    public GroupAssert assertGroup(Set<GroupingAttribute<?>> key) {
        isNotNull();
        return GroupAssert.assertThat(actual.getGroup(key));
    }
}
