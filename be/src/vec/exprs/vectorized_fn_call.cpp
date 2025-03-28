// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "vec/exprs/vectorized_fn_call.h"

#include <string_view>

#include "common/status.h"
#include "exprs/anyval_util.h"
#include "exprs/rpc_fn.h"
#include "fmt/format.h"
#include "fmt/ranges.h"
#include "udf/udf_internal.h"
#include "vec/data_types/data_type_nullable.h"
#include "vec/data_types/data_type_number.h"
#include "vec/functions/function_java_udf.h"
#include "vec/functions/function_rpc.h"
#include "vec/functions/simple_function_factory.h"

namespace doris::vectorized {

VectorizedFnCall::VectorizedFnCall(const doris::TExprNode& node) : VExpr(node) {}

doris::Status VectorizedFnCall::prepare(doris::RuntimeState* state,
                                        const doris::RowDescriptor& desc, VExprContext* context) {
    RETURN_IF_ERROR_OR_PREPARED(VExpr::prepare(state, desc, context));
    ColumnsWithTypeAndName argument_template;
    argument_template.reserve(_children.size());
    std::vector<std::string_view> child_expr_name;
    for (auto child : _children) {
        auto column = child->data_type()->create_column();
        argument_template.emplace_back(std::move(column), child->data_type(), child->expr_name());
        child_expr_name.emplace_back(child->expr_name());
    }
    if (_fn.binary_type == TFunctionBinaryType::RPC) {
        _function = FunctionRPC::create(_fn, argument_template, _data_type);
    } else if (_fn.binary_type == TFunctionBinaryType::JAVA_UDF) {
#ifdef LIBJVM
        _function = JavaFunctionCall::create(_fn, argument_template, _data_type);
#else
        return Status::InternalError("Java UDF is disabled since no libjvm is found!");
#endif
    } else {
        _function = SimpleFunctionFactory::instance().get_function(_fn.name.function_name,
                                                                   argument_template, _data_type);
    }
    if (_function == nullptr) {
        return Status::InternalError("Function {} is not implemented", _fn.name.function_name);
    }
    VExpr::register_function_context(state, context);
    _expr_name = fmt::format("{}({})", _fn.name.function_name, child_expr_name);

    return Status::OK();
}

doris::Status VectorizedFnCall::open(doris::RuntimeState* state, VExprContext* context,
                                     FunctionContext::FunctionStateScope scope) {
    RETURN_IF_ERROR(VExpr::open(state, context, scope));
    RETURN_IF_ERROR(VExpr::init_function_context(context, scope, _function));
    return Status::OK();
}

void VectorizedFnCall::close(doris::RuntimeState* state, VExprContext* context,
                             FunctionContext::FunctionStateScope scope) {
    VExpr::close_function_context(context, scope, _function);
    VExpr::close(state, context, scope);
}

doris::Status VectorizedFnCall::execute(VExprContext* context, doris::vectorized::Block* block,
                                        int* result_column_id) {
    // TODO: not execute const expr again, but use the const column in function context
    doris::vectorized::ColumnNumbers arguments(_children.size());
    for (int i = 0; i < _children.size(); ++i) {
        int column_id = -1;
        RETURN_IF_ERROR(_children[i]->execute(context, block, &column_id));
        arguments[i] = column_id;
    }
    // call function
    size_t num_columns_without_result = block->columns();
    // prepare a column to save result
    block->insert({nullptr, _data_type, _expr_name});
    RETURN_IF_ERROR(_function->execute(context->fn_context(_fn_context_index), *block, arguments,
                                       num_columns_without_result, block->rows(), false));
    *result_column_id = num_columns_without_result;
    return Status::OK();
}

const std::string& VectorizedFnCall::expr_name() const {
    return _expr_name;
}

std::string VectorizedFnCall::debug_string() const {
    std::stringstream out;
    out << "VectorizedFn[";
    out << _expr_name;
    out << "]{";
    bool first = true;
    for (VExpr* input_expr : children()) {
        if (first) {
            first = false;
        } else {
            out << ",";
        }
        out << input_expr->debug_string();
    }
    out << "}";
    return out.str();
}

std::string VectorizedFnCall::debug_string(const std::vector<VectorizedFnCall*>& agg_fns) {
    std::stringstream out;
    out << "[";
    for (int i = 0; i < agg_fns.size(); ++i) {
        out << (i == 0 ? "" : " ") << agg_fns[i]->debug_string();
    }
    out << "]";
    return out.str();
}
} // namespace doris::vectorized
